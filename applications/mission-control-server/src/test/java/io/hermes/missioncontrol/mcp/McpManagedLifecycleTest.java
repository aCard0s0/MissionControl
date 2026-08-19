package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The managed-Compose state machine: desired state, {@code operation_state},
 * {@code applied_revision}, and the error recorded when Compose fails. In production these
 * run on a virtual-thread executor with nothing to await, so a row left stuck in
 * {@code starting} — or an operation that swallowed its error — would ship unnoticed.
 */
class McpManagedLifecycleTest {

  private static final AppProperties LIVE_MODE =
      new AppProperties("", "unix:///var/run/docker.sock", "hermes/agent", "hermes", "test", true);

  /** Runs submitted work on the calling thread, so operations finish before create/start returns. */
  private static final class DirectExecutorService extends AbstractExecutorService {
    private volatile boolean shutdown;

    @Override public void shutdown() { shutdown = true; }
    @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
    @Override public boolean isShutdown() { return shutdown; }
    @Override public boolean isTerminated() { return shutdown; }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    @Override public void execute(Runnable command) { command.run(); }
  }

  private SqliteTestDatabase database;
  private McpServerRepository repository;
  private ComposeStackManager compose;
  private HostService hosts;
  private McpWiring.Graph graph;
  private McpRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new McpServerRepository(database.jdbc());
    compose = mock(ComposeStackManager.class);
    hosts = mock(HostService.class);
    when(hosts.ref(anyString())).thenReturn(new DockerHostRef("dh-local", "unix:///sock"));

    graph = McpWiring.graph(repository,
        new RetainedResourceRepository(database.jdbc()),
        new AgentMcpLinkRepository(database.jdbc()),
        hosts, mock(DockerGateway.class), compose, LIVE_MODE, new DirectExecutorService());
    service = graph.service();
  }

  @AfterEach
  void tearDown() throws Exception {
    graph.close();
    database.close();
  }

  private McpServerDto createManaged() {
    return service.create(new McpServerRequest(
        "files", "a managed server", "managed", HostService.LOCAL_HOST_ID,
        "http", null, "mcp/filesystem:latest", null,
        List.of(), List.of(), null, List.of(),
        1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of()));
  }

  @Test
  void aManagedServerProvisionsToStoppedAndIdle() {
    McpServerDto created = createManaged();

    ServerRow row = repository.findById(created.id()).orElseThrow();
    assertEquals("stopped", row.desiredState());
    assertEquals("idle", row.operationState());
  }

  @Test
  void startingDrivesTheRowToRunningAndIdleAndAdvancesTheAppliedRevision() {
    McpServerDto created = createManaged();

    service.start(created.id());

    ServerRow row = repository.findById(created.id()).orElseThrow();
    assertEquals("running", row.desiredState());
    // runtime_state is not asserted: it reflects what the daemon actually reports, and a
    // mocked DockerGateway has no container, so it settles at "missing" by design
    // idle is what releases the row for the next operation — stuck at "starting" is the bug
    assertEquals("idle", row.operationState());
    assertEquals(row.revision(), row.appliedRevision());
    assertTrue(row.operationError() == null || row.operationError().isBlank());
  }

  @Test
  void stoppingDrivesTheRowBackToStoppedAndIdle() {
    McpServerDto created = createManaged();
    service.start(created.id());

    service.stop(created.id());

    ServerRow row = repository.findById(created.id()).orElseThrow();
    assertEquals("stopped", row.desiredState());
    assertEquals("idle", row.operationState());
  }

  @Test
  void aFailingComposeCallRecordsTheErrorAndDoesNotLeaveTheRowBusy() {
    McpServerDto created = createManaged();
    org.mockito.Mockito.doThrow(new RuntimeException("compose up failed: no such image"))
        .when(compose).execute(anyString(), any(), any(), any());

    service.start(created.id());

    ServerRow row = repository.findById(created.id()).orElseThrow();
    assertEquals("error", row.operationState());
    // an operation that failed silently leaves the operator with no way to know why
    assertNotNull(row.operationError());
    assertTrue(row.operationError().contains("compose up failed"));
  }

  @Test
  void aRowLeftInErrorCanStillBeRetried() {
    McpServerDto created = createManaged();
    org.mockito.Mockito.doThrow(new RuntimeException("transient daemon failure"))
        .when(compose).execute(anyString(), any(), any(), any());
    service.start(created.id());
    assertEquals("error", repository.findById(created.id()).orElseThrow().operationState());

    // re-stub with doReturn: a when(...) call here would re-invoke the throwing mock
    org.mockito.Mockito.doReturn("ok")
        .when(compose).execute(anyString(), any(), any(), any());
    service.start(created.id());

    ServerRow row = repository.findById(created.id()).orElseThrow();
    assertEquals("idle", row.operationState());
    assertEquals("running", row.desiredState());
  }
}
