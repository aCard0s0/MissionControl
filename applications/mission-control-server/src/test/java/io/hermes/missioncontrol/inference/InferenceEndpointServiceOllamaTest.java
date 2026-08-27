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
    service = new InferenceEndpointService(repository, new OllamaProtocolClient(new ObjectMapper()));
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
    assertEquals("ollama not reachable — check the address and that the server is running "
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
    route("/api/tags", 200, """
        {"models":[{"name":"llama3:8b","size":10,"details":{"family":"llama"}}]}
        """);

    List<EndpointModelDto> models = service.models(ID);

    assertEquals("llama3:8b", models.getFirst().name());
    assertTrue(requests.contains("GET /api/tags"));
  }

  @Test
  void anUnhappyTagsResponseIsAnUpstreamFailureCarryingOnlyItsFirstLine() {
    route("/api/tags", 503, "server busy\nstack trace with /home/ops/secrets in it");

    UpstreamUnavailableException failure =
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID));

    assertEquals("ollama returned HTTP 503: server busy", failure.getMessage());
  }

  @Test
  void anUnparseableTagsBodyIsAnUpstreamFailureNotAServerError() {
    route("/api/tags", 200, "not json at all");

    assertEquals("unexpected response from ollama /api/tags",
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID)).getMessage());
  }

  @Test
  void anUnreachableProviderMakesAModelListingA503() throws Exception {
    providerExists("http://127.0.0.1:" + closedPort());

    UpstreamUnavailableException failure =
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID));

    assertTrue(failure.getMessage().startsWith("ollama not reachable:"), failure.getMessage());
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

  // ── pulls ───────────────────────────────────────────────────────────────

  @Test
  void aPullIsReportedAsPullingWhileItRunsAndDoneWhenItFinishes() throws Exception {
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
        && body.contains("\"stream\":false")), bodies.toString());
  }

  @Test
  void aRejectedPullIsRecordedAsAnErrorWithTheFirstLineOfTheResponse() throws Exception {
    route("/api/pull", 404, "model not found\nsecond line nobody needs");

    service.pull(ID, "nope:latest");

    PullStatusDto status = awaitStatus("nope:latest", "error");
    assertEquals("model not found", status.detail());
  }

  @Test
  void aPullAgainstAnUnreachableProviderEndsAsAnErrorRatherThanHangingOnPulling() throws Exception {
    providerExists("http://127.0.0.1:" + closedPort());

    service.pull(ID, "llama3:8b");

    assertFalse(awaitStatus("llama3:8b", "error").detail().isBlank());
  }

  @Test
  void deletingAModelSendsADeleteCarryingTheModelAndForgetsItsPullState() throws Exception {
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
    route("/api/delete", 500, "cannot remove: in use");

    assertEquals("ollama returned HTTP 500: cannot remove: in use",
        assertThrows(UpstreamUnavailableException.class,
            () -> service.deleteModel(ID, "llama3:8b")).getMessage());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void providerExists(String url) {
    EndpointRow row = new EndpointRow(ID, "box", url, "ollama");
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
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      PullStatusDto current = service.pulls(ID).stream()
          .filter(status -> model.equals(status.model()))
          .findFirst()
          .orElse(null);
      if (current != null && expected.equals(current.status())) return current;
      Thread.sleep(20);
    }
    throw new AssertionError("pull of " + model + " never reached " + expected
        + " (last seen " + service.pulls(ID) + ")");
  }
}
