package io.hermes.missioncontrol.mcp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The request the probe actually sends, and the verdict it writes, driven against a loopback
 * stub MCP endpoint.
 *
 * <p>{@link McpHealthProbeTest} covers how a response becomes a verdict; this covers getting
 * there: streamable HTTP is probed with an {@code initialize} POST while legacy SSE is probed
 * with a GET, configured headers have to travel with it, and every failure has to land on the
 * record as an operator-facing reason rather than escaping to the caller.
 *
 * <p>The local-host path is covered too, with the /proc lookup for Mission Control's own
 * container id substituted so the verdict does not depend on whether the test run is itself
 * containerized.
 */
class McpHealthProbeRequestTest {

  private static final String ID = "srv-1";
  private static final String REMOTE_HOST = "dh-remote";
  private static final String OWN_CONTAINER = "a".repeat(64);

  private HttpServer server;
  private String baseUrl;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private final List<String> bodies = new CopyOnWriteArrayList<>();
  private final List<Map<String, List<String>>> headers = new CopyOnWriteArrayList<>();

  private McpServerRepository repository;
  private McpConfigStore configs;
  private HostService hosts;
  private DockerGateway docker;
  private McpHealthProbe probe;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    repository = mock(McpServerRepository.class);
    configs = mock(McpConfigStore.class);
    hosts = mock(HostService.class);
    docker = mock(DockerGateway.class);
    probe = new McpHealthProbe(repository, configs, hosts, docker);
    when(configs.materialize(any())).thenReturn(Map.of());
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  // ── dispatch ────────────────────────────────────────────────────────────

  @Test
  void aStdioServerCannotBeProbedAtAll() {
    // there is no endpoint to reach: the transport is a pipe to a child process
    ServerRow stdio = row("stdio", "running", REMOTE_HOST);

    assertEquals("reachability checks do not apply to stdio MCP servers",
        assertThrows(IllegalArgumentException.class, () -> probe.check(stdio)).getMessage());
    verifyNoInteractions(repository);
  }

  // ── managed servers ─────────────────────────────────────────────────────

  @Test
  void aStreamableHttpServerIsProbedWithAnInitializeHandshake() {
    route("/mcp", 200, "application/json", "{}");
    managedConfig("http", baseUrl + "/mcp");

    probe.check(row("managed", "running", REMOTE_HOST));

    assertEquals(List.of("POST /mcp"), requests);
    assertTrue(bodies.getFirst().contains("\"method\":\"initialize\""), bodies.toString());
    assertTrue(bodies.getFirst().contains("\"protocolVersion\":\"2025-06-18\""));
    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void aLegacySseServerIsProbedWithAGetBecausePostWouldBe405() {
    route("/sse", 200, "text/event-stream", "");
    managedConfig("sse", baseUrl + "/sse");

    probe.check(row("managed", "running", REMOTE_HOST));

    assertEquals(List.of("GET /sse"), requests);
    assertEquals("", bodies.getFirst());
    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void configuredHeadersTravelWithTheProbeSoAnAuthenticatedServerIsNotReportedAsBroken() {
    route("/mcp", 200, "application/json", "{}");
    managedConfig("http", baseUrl + "/mcp");
    when(configs.materialize(any())).thenReturn(Map.of("X-Api-Key", "decrypted-secret"));

    probe.check(row("managed", "running", REMOTE_HOST));

    assertEquals(List.of("decrypted-secret"), headers.getFirst().get("X-api-key"));
    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void aRejectedHostHeaderLandsOnTheRecordAsTheImageFaultItIs() {
    route("/mcp", 421, "text/plain", "misdirected");
    managedConfig("http", baseUrl + "/mcp");

    probe.check(row("managed", "running", REMOTE_HOST));

    assertTrue(errorWrittenToTheRecord().contains("Host header"));
  }

  @Test
  void anSseEntryAnsweringJsonIsReportedAsATransportMismatch() {
    route("/sse", 200, "application/json", "{}");
    managedConfig("sse", baseUrl + "/sse");

    probe.check(row("managed", "running", REMOTE_HOST));

    assertTrue(errorWrittenToTheRecord().contains("rather than an SSE stream"));
  }

  @Test
  void aServerThatIsNotRunningIsNeverContacted() {
    managedConfig("http", baseUrl + "/mcp");

    probe.check(row("managed", "stopped", REMOTE_HOST));

    verify(repository).updateCheck(eq(ID), eq("error"), eq("server is not running"), anyLong(), isNull());
    assertTrue(requests.isEmpty());
  }

  @Test
  void aManagedServerOnARemoteHostWithNoCrossHostUrlCannotBeChecked() {
    // the MCP network is local to each daemon, so a remote service name resolves nowhere
    managedConfig("http", null);

    probe.check(row("managed", "running", REMOTE_HOST));

    assertEquals("a managed server on a remote Docker host can only be checked through a cross-host URL",
        errorWrittenToTheRecord());
    verifyNoInteractions(docker);
  }

  @Test
  void aBlankCrossHostUrlIsTreatedAsAbsentRatherThanProbed() {
    managedConfig("http", "   ");

    probe.check(row("managed", "running", REMOTE_HOST));

    assertTrue(errorWrittenToTheRecord().contains("cross-host URL"));
    assertTrue(requests.isEmpty());
  }

  @Test
  void aRefusedConnectionIsRecordedAgainstTheTargetThatWasTried() throws Exception {
    String dead = "http://127.0.0.1:" + closedPort() + "/mcp";
    managedConfig("http", dead);

    probe.check(row("managed", "running", REMOTE_HOST));

    // the operator needs to see which address failed, not just that something did
    String error = errorWrittenToTheRecord();
    assertTrue(error.startsWith(dead + " — "), error);
    verify(repository, never()).updateCheck(anyString(), eq("connected"), isNull(), anyLong(), anyLong());
  }

  // ── the local-host path: attaching to the MCP network ───────────────────
  //
  // On the local daemon a managed server is probed by its Compose service name, which only
  // resolves once Mission Control's own container has joined the MCP network. The container id
  // comes from this process' /proc/self/mountinfo, so it is substituted here rather than left to
  // decide the outcome by whether the test run happens to be containerized.

  @Test
  void aLocalManagedServerIsAttachedToTheMcpNetworkAndProbedByItsServiceName() {
    route("/mcp", 200, "application/json", "{}");
    when(hosts.urlOf(HostService.LOCAL_HOST_ID)).thenReturn("unix:///sock");
    McpHealthProbe attaching = probeOwnedBy(OWN_CONTAINER);
    // 'localhost' stands in for the Compose service name so the probe reaches the stub without
    // needing a Docker network to make a service name resolvable
    localManagedConfig();

    attaching.check(row("managed", "running", HostService.LOCAL_HOST_ID, "localhost"));

    verify(docker).connectNetwork("unix:///sock", OWN_CONTAINER, ComposeStackRenderer.NETWORK);
    assertEquals(List.of("POST /mcp"), requests);
    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void aLocalProbeWithNoContainerToAttachIsReportedRatherThanThrown() {
    // running Mission Control outside a container is a supported development mode; the check
    // simply cannot work there, and that has to reach the operator as a verdict
    localManagedConfig();

    probeOwnedBy(null).check(row("managed", "running", HostService.LOCAL_HOST_ID, "mcp-files"));

    assertEquals("cannot reach the MCP network: Mission Control is not running inside a container",
        errorWrittenToTheRecord());
    verifyNoInteractions(docker);
    assertTrue(requests.isEmpty());
  }

  @Test
  void aFailedNetworkAttachIsRecordedAndStopsTheProbe() {
    when(hosts.urlOf(HostService.LOCAL_HOST_ID)).thenReturn("unix:///sock");
    doThrow(new UpstreamUnavailableException("docker host not connected"))
        .when(docker).connectNetwork(anyString(), anyString(), anyString());
    localManagedConfig();

    probeOwnedBy(OWN_CONTAINER).check(row("managed", "running", HostService.LOCAL_HOST_ID, "localhost"));

    assertEquals("docker host not connected", errorWrittenToTheRecord());
    assertTrue(requests.isEmpty(), "nothing may be probed once the network attach failed");
  }

  // ── external servers ────────────────────────────────────────────────────

  @Test
  void anExternalServerIsProbedWithAHeadRequestAndItsHeaders() {
    route("/mcp", 200, "application/json", "");
    externalConfig(baseUrl + "/mcp");
    when(configs.materialize(any())).thenReturn(Map.of("Authorization", "Bearer token"));

    probe.check(row("external", "running", REMOTE_HOST));

    assertEquals(List.of("HEAD /mcp"), requests);
    assertEquals(List.of("Bearer token"), headers.getFirst().get("Authorization"));
    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void anExternalServerAnsweringAnythingAtAllCountsAsReachable() {
    // an external endpoint may legitimately refuse a HEAD; that it answered is the signal
    route("/mcp", 405, "text/plain", "");
    externalConfig(baseUrl + "/mcp");

    probe.check(row("external", "running", REMOTE_HOST));

    verify(repository).updateCheck(eq(ID), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void anExternalServerThatCannotBeReachedIsRecordedAsAnError() throws Exception {
    externalConfig("http://127.0.0.1:" + closedPort() + "/mcp");

    probe.check(row("external", "running", REMOTE_HOST));

    // the reason has to survive an exception that carries no message of its own,
    // which is what a refused connection often is
    String error = errorWrittenToTheRecord();
    assertTrue(error != null && !error.isBlank(), "recorded reason: " + error);
    verify(repository, never()).updateCheck(anyString(), eq("connected"), isNull(), anyLong(), anyLong());
  }

  @Test
  void aFailureCarryingNoMessageIsStillRecordedWithItsType() {
    // ConnectException from the JDK client is frequently message-less; "error" with a
    // blank reason tells an operator nothing
    assertEquals("ConnectException", McpHealthProbe.rootMessage(new ConnectException()));
    assertEquals("Connection refused",
        McpHealthProbe.rootMessage(new ConnectException("Connection refused")));
    // the cause is what carries the detail when the wrapper itself has no message
    assertEquals("Network is unreachable",
        McpHealthProbe.rootMessage(new IOException((String) null, new SocketException("Network is unreachable"))));
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static ServerRow row(String kind, String runtimeState, String hostId) {
    return row(kind, runtimeState, hostId, "mcp-files");
  }

  private static ServerRow row(String kind, String runtimeState, String hostId, String serviceKey) {
    return new ServerRow(ID, "Files", null, kind, hostId, serviceKey, "{}",
        "running", runtimeState, "idle", null, 1L, 1L, null, "unknown", null, null, null, 0L, 0L);
  }

  /** A probe whose own container id is decided by the test rather than by /proc. */
  private McpHealthProbe probeOwnedBy(String containerId) {
    return new McpHealthProbe(repository, configs, hosts, docker) {
      @Override
      String ownNetworkContainerId() {
        return containerId;
      }
    };
  }

  /**
   * A managed record on the local daemon. The connection URL the probe builds is
   * {@code http://<serviceKey>:<internalPort><path>}, with the service key coming from the row,
   * so the port here is what points it at the stub.
   */
  private void localManagedConfig() {
    when(configs.read(any())).thenReturn(new StoredConfig("http", null, "example/files:1", null,
        List.of(), List.of(), null, List.of(), server.getAddress().getPort(), null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of()));
  }

  private void managedConfig(String transport, String crossHostUrl) {
    when(configs.read(any())).thenReturn(new StoredConfig(transport, null, "example/files:1", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", crossHostUrl,
        List.of(), List.of(), List.of(), null, List.of()));
  }

  private void externalConfig(String url) {
    when(configs.read(any())).thenReturn(new StoredConfig("http", url, null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of()));
  }

  /** The reason written onto the record by the probe. */
  private String errorWrittenToTheRecord() {
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(repository).updateCheck(eq(ID), eq("error"), error.capture(), anyLong(), isNull());
    return error.getValue();
  }

  private void route(String path, int status, String contentType, String body) {
    server.createContext(path, exchange -> {
      requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      bodies.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
      headers.add(Map.copyOf(exchange.getRequestHeaders()));
      respond(exchange, status, contentType, body);
    });
  }

  private static void respond(HttpExchange exchange, int status, String contentType, String body)
      throws IOException {
    byte[] payload = body.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", contentType);
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
}
