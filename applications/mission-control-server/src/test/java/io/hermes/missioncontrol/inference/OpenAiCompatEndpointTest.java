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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.inference.InferenceEndpointRepository.EndpointRow;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The second endpoint kind: an OpenAI-compatible server, driven against a loopback stub.
 *
 * <p>Two things here are worth more than the rest. The first is that a server answering BOTH
 * protocols is detected as ollama — real ollama serves {@code /v1} as well as its own API, so
 * probe order is the only thing standing between it and being filed as "openai" with its pull
 * and delete silently stripped. The second is that pull and delete are refused for this kind
 * rather than attempted, because there is nothing on the other end to attempt them against.
 */
class OpenAiCompatEndpointTest {

  private static final String ID = "mp-1";

  /** LM Studio and llama.cpp both answer roughly this, and neither sends more. */
  private static final String MODELS_BODY = """
      {"object":"list","data":[
        {"id":"qwen3-8b","object":"model","created":1750000000,"owned_by":"organization_owner"},
        {"id":"nomic-embed-text","object":"model"}
      ]}""";

  private HttpServer server;
  private String baseUrl;
  private final List<String> requests = new CopyOnWriteArrayList<>();

  private InferenceEndpointRepository repository;
  private InferenceEndpointService service;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    repository = mock(InferenceEndpointRepository.class);
    ObjectMapper mapper = new ObjectMapper();
    service = new InferenceEndpointService(repository,
        List.of(new OllamaProtocolClient(mapper), new OpenAiCompatClient(mapper)));
    when(repository.urlExists(anyString())).thenReturn(false);
  }

  @AfterEach
  void stop() {
    service.shutdownPulls();
    server.stop(0);
  }

  // ── parsing ─────────────────────────────────────────────────────────────

  @Test
  void modelsCarryOnlyWhatTheProtocolReports() throws Exception {
    List<EndpointModelDto> models =
        OpenAiCompatClient.parseModels(new ObjectMapper().readTree(MODELS_BODY));

    assertEquals(2, models.size());
    EndpointModelDto first = models.getFirst();
    assertEquals("qwen3-8b", first.name());
    // /v1/models has no equivalent of ollama's size/family/parameter_size
    assertNull(first.sizeBytes());
    assertNull(first.family());
    assertNull(first.parameterSize());
    // created is unix SECONDS on the wire; modifiedAt is millis
    assertEquals(1_750_000_000_000L, first.modifiedAt());

    // LM Studio and llama.cpp both omit created; the row still has to render
    assertEquals("nomic-embed-text", models.get(1).name());
    assertNull(models.get(1).modifiedAt());
  }

  @Test
  void entriesWithoutAnIdAreSkippedRatherThanRenderedBlank() throws Exception {
    List<EndpointModelDto> models = OpenAiCompatClient.parseModels(new ObjectMapper().readTree(
        "{\"data\":[{\"object\":\"model\"},{\"id\":\"\"},{\"id\":\"real\"}]}"));
    assertEquals(List.of("real"), models.stream().map(EndpointModelDto::name).toList());
  }

  @Test
  void anEmptyOrShapelessBodyIsNoModelsRatherThanAnError() throws Exception {
    assertTrue(OpenAiCompatClient.parseModels(
        new ObjectMapper().readTree("{\"data\":[]}")).isEmpty());
    assertTrue(OpenAiCompatClient.parseModels(new ObjectMapper().readTree("{}")).isEmpty());
  }

  // ── detection ───────────────────────────────────────────────────────────

  @Test
  void aServerThatOnlyAnswersV1IsRegisteredAsOpenai() {
    route("/v1/models", 200, MODELS_BODY);

    InferenceEndpointDto added = service.add("lm-studio", baseUrl);

    assertEquals("openai", added.kind());
    assertEquals("connected", added.status());
    // the protocol has no version endpoint, so there is nothing to report
    assertNull(added.version());
    assertFalse(added.canManageModels());
  }

  /**
   * The ordering guard. Ollama serves an OpenAI-compatible /v1 alongside its own API, so a
   * detector that asked /v1/models first would file every ollama server as "openai" and take
   * its pull and delete away.
   */
  @Test
  void aServerAnsweringBothProtocolsIsDetectedAsOllama() {
    route("/api/version", 200, "{\"version\":\"0.5.7\"}");
    route("/v1/models", 200, MODELS_BODY);

    InferenceEndpointDto added = service.add("box", baseUrl);

    assertEquals("ollama", added.kind());
    assertEquals("0.5.7", added.version());
    assertTrue(added.canManageModels());
  }

  @Test
  void theProtocolIsReportedButNotStored() {
    route("/v1/models", 200, MODELS_BODY);

    assertEquals("openai", service.add("lm-studio", baseUrl).kind());

    // the row carries a url and nothing about the protocol: what answers there is the
    // server's business and is re-probed, so it cannot go stale behind a swapped server
    ArgumentCaptor<EndpointRow> saved = ArgumentCaptor.forClass(EndpointRow.class);
    verify(repository).insert(saved.capture());
    assertEquals(baseUrl, saved.getValue().url());
    assertEquals(3, EndpointRow.class.getRecordComponents().length);
  }

  /**
   * Registering must not require the server to be up. A Mac that is asleep is still an
   * endpoint you want listed — it reports an error until it answers, and the probe works out
   * what it is then. Detecting at insert time is exactly what made this impossible before.
   */
  @Test
  void anEndpointThatAnswersNothingIsStillRegisteredAndReportsWhy() {
    // the stub is listening but serves neither /api/version nor /v1/models
    InferenceEndpointDto added = service.add("switched-off", baseUrl);

    verify(repository).insert(any());
    assertEquals("error", added.status());
    assertNull(added.kind(), "nothing answered, so the protocol is unknown — not guessed");
    assertFalse(added.canManageModels());
    assertTrue(added.detail().contains("no model server answered"), added.detail());
  }

  // ── capability ──────────────────────────────────────────────────────────

  @Test
  void modelsAreListedThroughV1ForAnOpenaiEndpoint() {
    endpointExists();
    route("/v1/models", 200, MODELS_BODY);

    List<EndpointModelDto> models = service.models(ID);

    assertEquals("qwen3-8b", models.getFirst().name());
    assertTrue(requests.contains("GET /v1/models"), requests.toString());
  }

  @Test
  void pullIsRefusedForAnOpenaiEndpointRatherThanAttempted() {
    endpointExists();
    route("/v1/models", 200, MODELS_BODY);   // detected as openai

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.pull(ID, "qwen3-8b"));

    assertTrue(failure.getMessage().contains("cannot add or remove models"), failure.getMessage());
    // refused on the request thread: no pull state, so no chip promising work that never runs
    assertTrue(service.pulls(ID).isEmpty());
    // the /v1/models detection probe is expected; no pull or delete followed it
    assertEquals(List.of("GET /v1/models"), requests);
  }

  @Test
  void deleteIsRefusedForAnOpenaiEndpointRatherThanAttempted() {
    endpointExists();
    route("/v1/models", 200, MODELS_BODY);   // detected as openai

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> service.deleteModel(ID, "qwen3-8b"));

    assertTrue(failure.getMessage().contains("cannot delete models"), failure.getMessage());
    // the /v1/models detection probe is expected; no pull or delete followed it
    assertEquals(List.of("GET /v1/models"), requests);
  }

  @Test
  void startAndStopAreRefusedButWhatIsResidentIsSimplyEmpty() {
    endpointExists();
    route("/v1/models", 200, MODELS_BODY);   // detected as openai

    assertTrue(assertThrows(IllegalArgumentException.class, () -> service.load(ID, "qwen3-8b"))
        .getMessage().contains("cannot load models"));
    assertTrue(assertThrows(IllegalArgumentException.class, () -> service.unload(ID, "qwen3-8b"))
        .getMessage().contains("cannot unload models"));
    // empty rather than refused: "cannot report" and "nothing resident" read the same on the
    // page, and a 400 here would make the panel's poll toast on every tick
    assertTrue(service.running(ID).isEmpty());
    assertEquals(List.of("GET /v1/models"), requests);
  }

  @Test
  void anUnhappyV1IsADependencyFailureNotABug() {
    endpointExists();
    route("/v1/models", 503, "overloaded");

    // /v1/models is also the detector, so a 503 there means the protocol never resolves
    assertTrue(assertThrows(UpstreamUnavailableException.class, () -> service.models(ID))
        .getMessage().contains("no model server is answering"));
  }

  @Test
  void aBodyThatIsNotJsonIsReportedAsAnUnexpectedResponse() {
    endpointExists();
    route("/v1/models", 200, "<html>not json</html>");

    assertEquals("unexpected response from /v1/models",
        assertThrows(UpstreamUnavailableException.class, () -> service.models(ID)).getMessage());
  }

  @Test
  void listingAnEndpointThatIsNotAnsweringIsA503NotAnInternalError() {
    endpointExists();   // the stub serves neither protocol

    assertTrue(assertThrows(UpstreamUnavailableException.class, () -> service.models(ID))
        .getMessage().contains("no model server is answering"));
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void endpointExists() {
    EndpointRow row = new EndpointRow(ID, "box", baseUrl);
    when(repository.findById(ID)).thenReturn(Optional.of(row));
    when(repository.findAll()).thenReturn(List.of(row));
  }

  private void route(String path, int status, String body) {
    server.createContext(path, exchange -> {
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      byte[] payload = body.getBytes(UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
      try (var out = exchange.getResponseBody()) {
        out.write(payload);
      }
    });
  }
}
