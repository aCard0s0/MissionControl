package io.hermes.missioncontrol.inference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.inference.InferenceEndpointRepository.EndpointRow;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The half of the ollama integration that talks HTTP, driven against a loopback stub instead of
 * a real ollama.
 *
 * <p>{@link InferenceEndpointServiceTest} covers the pure parsing; everything here — the probe and
 * its cache, the 503 mapping for an unreachable or unhappy server, and the background pull state
 * machine — was previously reachable only with an ollama actually running.
 */
class InferenceEndpointServiceOllamaTest {

  private static final String ID = "mp-1";

  private HttpServer server;
  private String baseUrl;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();

  private InferenceEndpointRepository repository;
  private InferenceEndpointService service;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    repository = mock(InferenceEndpointRepository.class);
    ObjectMapper mapper = new ObjectMapper();
    // both clients, as Spring wires them — so these tests also cover that an ollama server
    // (which serves /v1 too) is still detected and dispatched as ollama
    service = new InferenceEndpointService(repository,
        List.of(new OllamaProtocolClient(mapper), new OpenAiCompatClient(mapper)));
    providerExists(baseUrl);
  }

  @AfterEach
  void stop() {
    service.shutdownPulls();
    server.stop(0);
  }

  // ── probing ─────────────────────────────────────────────────────────────

  @Test
  void aReachableProviderIsReportedAsConnectedWithItsVersion() {
    route("/api/version", 200, "{\"version\":\"0.5.1\"}");

    InferenceEndpointDto provider = service.list().getFirst();

    assertEquals("connected", provider.status());
    assertEquals("0.5.1", provider.version());
    assertNull(provider.detail());
  }

  @Test
  void aProviderAnsweringWithAnErrorStatusIsReportedWithAnActionableDetail() {
    // the dashboard shows detail verbatim, so it has to say what to check rather than repeat
    // an HTTP code the operator cannot act on
    route("/api/version", 500, "internal error");

    InferenceEndpointDto provider = service.check(ID);

    assertEquals("error", provider.status());
    assertNull(provider.version());
    // the reason quoted is the FIRST protocol's, which is the one carrying the connect-level
    // failure; the openai probe that also failed here has nothing more useful to say
    assertEquals("no model server answered — check the address and that the server is running "
        + "(ollama returned HTTP 500)", provider.detail());
  }

  @Test
  void anUnreachableProviderIsReportedAsErrorRatherThanFailingTheWholeList() throws Exception {
    // one dead provider must not take down the provider list
    providerExists("http://127.0.0.1:" + closedPort());

    List<InferenceEndpointDto> providers = service.list();

    assertEquals("error", providers.getFirst().status());
    // the detail used to stop at generic advice; it now names why the connect failed
    assertTrue(providers.getFirst().detail().contains("refused")
        || providers.getFirst().detail().contains("timed out"), providers.getFirst().detail());
  }

  @Test
  void aProbeIsCachedAndOnlyAnExplicitCheckForcesAFreshOne() {
    // list() runs on every dashboard poll; probing each provider every time would add three
    // seconds of latency per unreachable one
    AtomicInteger probes = counted("/api/version", "{\"version\":\"0.5.1\"}");

    service.list();
    service.list();
    assertEquals(1, probes.get());

    service.check(ID);
    assertEquals(2, probes.get());
  }

  // ── models ──────────────────────────────────────────────────────────────

  @Test
  void installedModelsComeFromTheTagsEndpoint() {
    ollamaAnswersItsVersion();
    route("/api/tags", 200, """
        {"models":[{"name":"llama3:8b","size":10,"details":{"family":"llama"}}]}
        """);

    List<EndpointModelDto> models = service.models(ID);

    assertEquals("llama3:8b", models.getFirst().name());
    assertTrue(requests.contains("GET /api/tags"));
  }

  @Test
  void anUnhappyTagsResponseIsAnUpstreamFailureCarryingOnlyItsFirstLine() {
    ollamaAnswersItsVersion();
    route("/api/tags", 503, "server busy\nstack trace with /home/ops/secrets in it");

    UpstreamUnavailableException failure =
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID));

    assertEquals("ollama returned HTTP 503: server busy", failure.getMessage());
  }

  @Test
  void anUnparseableTagsBodyIsAnUpstreamFailureNotAServerError() {
    ollamaAnswersItsVersion();
    route("/api/tags", 200, "not json at all");

    assertEquals("unexpected response from ollama /api/tags",
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID)).getMessage());
  }

  @Test
  void anUnreachableProviderMakesAModelListingA503() throws Exception {
    providerExists("http://127.0.0.1:" + closedPort());

    UpstreamUnavailableException failure =
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID));

    // still a 503; it now comes from resolving the protocol rather than from /api/tags
    assertTrue(failure.getMessage().contains("no model server is answering"),
        failure.getMessage());
  }

  // ── registry ────────────────────────────────────────────────────────────

  @Test
  void addingAProviderStoresTheNormalisedUrlAndProbesItStraightAway() {
    route("/api/version", 200, "{\"version\":\"0.5.1\"}");
    when(repository.urlExists(baseUrl)).thenReturn(false);

    InferenceEndpointDto added = service.add("box", baseUrl + "//");

    assertEquals("connected", added.status());
    assertEquals(baseUrl, added.url());
    verify(repository).insert(any(EndpointRow.class));
  }

  @Test
  void addingAProviderWhoseUrlIsAlreadyStoredIsRejectedAsABadRequest() {
    when(repository.urlExists(baseUrl)).thenReturn(true);

    assertEquals("a provider with this url already exists",
        assertThrows(IllegalArgumentException.class, () -> service.add("box", baseUrl)).getMessage());
    verify(repository, never()).insert(any());
  }

  @Test
  void anUnknownProviderIdIsANotFoundOnEveryEndpointThatTakesOne() {
    when(repository.findById(ID)).thenReturn(Optional.empty());

    assertThrows(NoSuchElementException.class, () -> service.check(ID));
    assertThrows(NoSuchElementException.class, () -> service.models(ID));
    assertThrows(NoSuchElementException.class, () -> service.delete(ID));
    assertThrows(NoSuchElementException.class, () -> service.pull(ID, "llama3:8b"));
    assertThrows(NoSuchElementException.class, () -> service.pulls(ID));
    assertThrows(NoSuchElementException.class, () -> service.deleteModel(ID, "llama3:8b"));
    assertThrows(NoSuchElementException.class, () -> service.running(ID));
    assertThrows(NoSuchElementException.class, () -> service.load(ID, "llama3:8b"));
    assertThrows(NoSuchElementException.class, () -> service.unload(ID, "llama3:8b"));
  }

  @Test
  void deletingAProviderForgetsItsCachedProbeAndItsPullState() throws Exception {
    AtomicInteger probes = counted("/api/version", "{\"version\":\"0.5.1\"}");
    route("/api/pull", 200, "{}");
    service.list();
    service.pull(ID, "llama3:8b");
    awaitStatus("llama3:8b", "done");

    service.delete(ID);

    verify(repository).delete(ID);
    assertTrue(service.pulls(ID).isEmpty(), "pull state must not outlive the provider");
    // and the cached probe is gone, so the next read of a re-added provider is fresh
    service.list();
    assertEquals(2, probes.get());
  }

  // ── what is loaded, and start / stop ────────────────────────────────────

  @Test
  void whatIsResidentComesFromThePsEndpoint() {
    ollamaAnswersItsVersion();
    route("/api/ps", 200, """
        {"models":[{"name":"llama3:8b","size_vram":5100000000}]}
        """);

    RunningModelDto running = service.running(ID).getFirst();

    assertEquals("llama3:8b", running.name());
    assertEquals(5_100_000_000L, running.sizeVramBytes());
    assertTrue(requests.contains("GET /api/ps"), requests.toString());
  }

  @Test
  void anUnparseablePsBodyIsAnUpstreamFailureNotAServerError() {
    ollamaAnswersItsVersion();
    route("/api/ps", 200, "not json at all");

    assertEquals("unexpected response from ollama /api/ps",
        assertThrows(UpstreamUnavailableException.class, () -> service.running(ID)).getMessage());
  }

  @Test
  void startPinsAModelInMemoryAndStopFreesItImmediately() {
    ollamaAnswersItsVersion();
    route("/api/generate", 200, "{\"done\":true}");

    service.load(ID, "llama3:8b");
    service.unload(ID, "llama3:8b");

    // keep_alive is the whole mechanism, and the two values are the feature: -1 holds the
    // model until something says otherwise, 0 frees the VRAM now. Ollama's default five
    // minutes would let a start the operator pressed wear off while they watched the row.
    assertTrue(bodies.contains("{\"model\":\"llama3:8b\",\"keep_alive\":-1,\"stream\":false}"),
        bodies.toString());
    assertTrue(bodies.contains("{\"model\":\"llama3:8b\",\"keep_alive\":0,\"stream\":false}"),
        bodies.toString());
  }

  @Test
  void aModelThatCannotBeLoadedIsAnUpstreamFailureCarryingTheReason() {
    ollamaAnswersItsVersion();
    route("/api/generate", 500, "model requires more system memory than is available");

    assertEquals("ollama returned HTTP 500: model requires more system memory than is available",
        assertThrows(UpstreamUnavailableException.class,
            () -> service.load(ID, "llama3:70b")).getMessage());
  }

  // ── pulls ───────────────────────────────────────────────────────────────

  @Test
  void aPullIsReportedAsPullingWhileItRunsAndDoneWhenItFinishes() throws Exception {
    ollamaAnswersItsVersion();
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/api/pull", exchange -> {
      record(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
          new String(exchange.getRequestBody().readAllBytes(), UTF_8));
      try {
        release.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      respond(exchange, 200, "{}");
    });

    service.pull(ID, "llama3:8b");

    // the state is recorded synchronously, so the dashboard sees 'pulling' on its next poll
    // rather than nothing at all
    assertEquals("pulling", statusOf("llama3:8b").status());
    release.countDown();
    assertEquals("done", awaitStatus("llama3:8b", "done").status());
    assertTrue(bodies.stream().anyMatch(body -> body.contains("\"model\":\"llama3:8b\"")
        && body.contains("\"stream\":true")), bodies.toString());
  }

  @Test
  void aStreamedPullReportsHowFarItHasGotWhileItRuns() throws Exception {
    ollamaAnswersItsVersion();
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/api/pull", exchange -> {
      record("POST /api/pull", new String(exchange.getRequestBody().readAllBytes(), UTF_8));
      exchange.sendResponseHeaders(200, 0);   // chunked, so the body arrives in pieces
      try (var out = exchange.getResponseBody()) {
        out.write("{\"status\":\"downloading\",\"total\":100,\"completed\":47}\n".getBytes(UTF_8));
        out.flush();
        release.await(5, TimeUnit.SECONDS);
        out.write("{\"status\":\"success\"}\n".getBytes(UTF_8));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    service.pull(ID, "llama3:8b");

    // the whole point of streaming: several gigabytes take minutes, and an unstreamed pull
    // could report nothing but "pulling" for all of them
    assertEquals("pulling", awaitDetail("llama3:8b", "47% · downloading").status());
    release.countDown();
    assertEquals("done", awaitStatus("llama3:8b", "done").status());
  }

  @Test
  void aPullThatFailsInsideAStreamedTwoHundredStillEndsAsAnError() throws Exception {
    ollamaAnswersItsVersion();
    // ollama commits to a 200 the moment it starts streaming, so a manifest that does not
    // exist arrives in the body. Unread, the chip would sit on "pulling" for ever.
    route("/api/pull", 200,
        "{\"status\":\"pulling manifest\"}\n{\"error\":\"file does not exist\"}\n");

    service.pull(ID, "nope:latest");

    assertEquals("file does not exist", awaitStatus("nope:latest", "error").detail());
  }

  @Test
  void aRejectedPullIsRecordedAsAnErrorWithTheFirstLineOfTheResponse() throws Exception {
    ollamaAnswersItsVersion();
    route("/api/pull", 404, "model not found\nsecond line nobody needs");

    service.pull(ID, "nope:latest");

    PullStatusDto status = awaitStatus("nope:latest", "error");
    assertEquals("model not found", status.detail());
  }

  @Test
  void aPullAgainstAnUnreachableProviderIsRefusedRatherThanQueued() throws Exception {
    providerExists("http://127.0.0.1:" + closedPort());

    // resolving the protocol fails first, so this never reaches the executor: the caller is
    // told now instead of watching a chip sit on "pulling" and then turn red
    assertThrows(UpstreamUnavailableException.class, () -> service.pull(ID, "llama3:8b"));
    assertTrue(service.pulls(ID).isEmpty());
  }

  @Test
  void deletingAModelSendsADeleteCarryingTheModelAndForgetsItsPullState() throws Exception {
    ollamaAnswersItsVersion();
    route("/api/pull", 200, "{}");
    route("/api/delete", 200, "{}");
    service.pull(ID, "llama3:8b");
    awaitStatus("llama3:8b", "done");

    service.deleteModel(ID, "llama3:8b");

    assertTrue(requests.contains("DELETE /api/delete"), requests.toString());
    assertTrue(bodies.stream().anyMatch(body -> body.equals("{\"model\":\"llama3:8b\"}")), bodies.toString());
    assertTrue(service.pulls(ID).isEmpty());
  }

  @Test
  void aFailedModelDeletionIsAnUpstreamFailure() {
    ollamaAnswersItsVersion();
    route("/api/delete", 500, "cannot remove: in use");

    assertEquals("ollama returned HTTP 500: cannot remove: in use",
        assertThrows(UpstreamUnavailableException.class,
            () -> service.deleteModel(ID, "llama3:8b")).getMessage());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  /**
   * Every operation resolves the protocol first, and for ollama that is {@code /api/version}.
   * A real server always answers it; the stub only does when a test says so.
   */
  private void ollamaAnswersItsVersion() {
    route("/api/version", 200, "{\"version\":\"0.5.7\"}");
  }

  private void providerExists(String url) {
    EndpointRow row = new EndpointRow(ID, "box", url);
    when(repository.findById(ID)).thenReturn(Optional.of(row));
    when(repository.findAll()).thenReturn(List.of(row));
    when(repository.urlExists(anyString())).thenReturn(false);
  }

  private void route(String path, int status, String body) {
    server.createContext(path, exchange -> {
      record(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(),
          new String(exchange.getRequestBody().readAllBytes(), UTF_8));
      respond(exchange, status, body);
    });
  }

  /** Same as {@link #route} but counts how many times the endpoint was hit. */
  private AtomicInteger counted(String path, String body) {
    AtomicInteger hits = new AtomicInteger();
    server.createContext(path, exchange -> {
      hits.incrementAndGet();
      record(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(), "");
      respond(exchange, 200, body);
    });
    return hits;
  }

  private void record(String request, String body) {
    requests.add(request);
    bodies.add(body);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] payload = body.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
    try (var out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  /** A port nothing is listening on, so a connection to it is refused immediately. */
  private static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private PullStatusDto statusOf(String model) {
    return service.pulls(ID).stream()
        .filter(status -> model.equals(status.model()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no pull state recorded for " + model));
  }

  private PullStatusDto awaitStatus(String model, String expected) throws InterruptedException {
    return await(model, status -> expected.equals(status.status()), expected);
  }

  /** Progress is reported through `detail`, so a mid-pull assertion waits on that instead. */
  private PullStatusDto awaitDetail(String model, String expected) throws InterruptedException {
    return await(model, status -> expected.equals(status.detail()), "detail " + expected);
  }

  private PullStatusDto await(String model, Predicate<PullStatusDto> settled, String expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      PullStatusDto current = service.pulls(ID).stream()
          .filter(status -> model.equals(status.model()))
          .findFirst()
          .orElse(null);
      if (current != null && settled.test(current)) return current;
      Thread.sleep(20);
    }
    throw new AssertionError("pull of " + model + " never reached " + expected
        + " (last seen " + service.pulls(ID) + ")");
  }
}
