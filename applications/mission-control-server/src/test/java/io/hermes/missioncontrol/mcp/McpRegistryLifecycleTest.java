package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The catalog's own rules, against the real schema.
 *
 * <p>Every managed mutation is recorded on the row and then handed to an executor for the
 * Compose work. The rules that decide <em>whether</em> it is recorded — kind and host are
 * immutable, one operation at a time, a linked server cannot be deleted, a lifecycle call only
 * applies to a managed record — are what this covers. The executor here queues operations
 * without running them, so those rules are observable without simulating a daemon; the Compose
 * work itself belongs to {@link McpComposeLifecycle}.
 */
class McpRegistryLifecycleTest {

  private static final AppProperties LIVE_MODE =
      new AppProperties("", "unix:///var/run/docker.sock", "hermes/agent", "hermes", "test", true);

  private SqliteTestDatabase database;
  private McpServerRepository repository;
  private RetainedResourceRepository retained;
  private AgentMcpLinkRepository links;
  private HostService hosts;
  private ComposeStackManager compose;
  private QueuedOperations operations;
  private McpWiring.Graph graph;
  private McpRegistryService service;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    JdbcTemplate jdbc = database.jdbc();
    repository = new McpServerRepository(jdbc);
    retained = new RetainedResourceRepository(jdbc);
    links = new AgentMcpLinkRepository(jdbc);
    hosts = mock(HostService.class);
    compose = mock(ComposeStackManager.class);
    operations = new QueuedOperations();
    when(hosts.ref("dh-local")).thenReturn(new DockerHostRef("dh-local", "unix:///sock"));
    graph = McpWiring.graph(repository, retained, links, hosts, mock(DockerGateway.class),
        compose, LIVE_MODE, operations);
    service = graph.service();
  }

  @AfterEach
  void tearDown() throws Exception {
    graph.close();
    database.close();
  }

  // ── create ──────────────────────────────────────────────────────────────

  @Test
  void aManagedRecordIsCreatedStoppedAndItsProvisioningIsQueued() {
    McpServerDto created = service.create(managed("Files", "dh-local"));

    ServerRow row = row(created.id());
    assertEquals("stopped", row.desiredState());
    assertEquals("missing", row.runtimeState());
    assertEquals("provisioning", row.operationState());
    // revision 1 with nothing applied yet: the stack has not been written
    assertEquals(1, row.revision());
    assertEquals(0, row.appliedRevision());
    assertTrue(row.serviceKey().startsWith("mcp-"), row.serviceKey());
    assertEquals(1, operations.queued());
  }

  @Test
  void anExternalRecordNeedsNoComposeWorkAtAll() {
    McpServerDto created = service.create(external("Remote docs"));

    ServerRow row = row(created.id());
    assertNull(row.serviceKey());
    assertEquals("unavailable", row.runtimeState());
    assertEquals("idle", row.operationState());
    // nothing to apply, so the record is never 'out of date'
    assertEquals(row.revision(), row.appliedRevision());
    assertEquals(0, operations.queued());
  }

  @Test
  void aManagedRecordMustNameAHostThisDashboardKnows() {
    when(hosts.ref("dh-ghost")).thenThrow(new NoSuchElementException("no such docker host"));

    assertThrows(NoSuchElementException.class, () -> service.create(managed("Files", "dh-ghost")));
    assertTrue(repository.findAll().isEmpty(), "no row may be left behind by a rejected create");
  }

  // ── update ──────────────────────────────────────────────────────────────

  @Test
  void kindAndHostAreImmutableBecauseNeitherCanBeMigratedInPlace() {
    String id = service.create(managed("Files", "dh-local")).id();
    settle(id);

    assertEquals("kind is immutable; create a new catalog record instead",
        assertThrows(IllegalArgumentException.class,
            () -> service.update(id, external("Files"))).getMessage());
    when(hosts.ref("dh-other")).thenReturn(new DockerHostRef("dh-other", "tcp://other:2375"));
    assertEquals("hostId is immutable; duplicate the server onto another host instead",
        assertThrows(IllegalArgumentException.class,
            () -> service.update(id, managed("Files", "dh-other"))).getMessage());
    assertEquals(1, row(id).revision(), "a refused update must not bump the revision");
  }

  @Test
  void aStoredSupportServiceCannotBeRenamedBecauseItsNameIsItsComposeIdentity() {
    String id = service.create(managedWith("Files", support("database", "pw"))).id();
    settle(id);
    // the rendered Compose service name is derived from it — that name is the hostname the main
    // container reaches the dependency by, and the prefix of every volume the dependency declares
    assertEquals(row(id).serviceKey() + "-database",
        ComposeStackRenderer.supportKey(row(id).serviceKey(), "database"));

    String message = assertThrows(IllegalArgumentException.class,
        () -> service.update(id, managedWith("Files", support("db", "")))).getMessage();

    assertTrue(message.contains("cannot be renamed"), message);
    assertTrue(message.contains("database"), message);
    assertTrue(message.contains("db"), message);
    assertEquals(1, row(id).revision(), "a refused update must not bump the revision");
  }

  @Test
  void aRefusedRenameLeavesTheStoredSupportServiceAndItsSecretUntouched() {
    String id = service.create(managedWith("Files", support("database", "pw"))).id();
    settle(id);

    assertThrows(IllegalArgumentException.class,
        () -> service.update(id, managedWith("Files", support("db", ""))));

    StoredSupportService stored = configOf(id).supportServices().getFirst();
    assertEquals("database", stored.name());
    assertEquals(Map.of("POSTGRES_PASSWORD", "pw"), decrypted(stored.environment()));
  }

  @Test
  void editingASupportServiceUnderItsOwnNameStillKeepsItsSecret() {
    // the editor never sends a stored secret back, so the blank submission below is what an
    // ordinary image bump looks like on the wire
    String id = service.create(managedWith("Files", support("database", "pw"))).id();
    settle(id);

    service.update(id, managedWith("Files",
        new SupportServiceRequest("database", "postgres:17-alpine", null, List.of(), List.of(),
            List.of(new ConfigValueInput("POSTGRES_PASSWORD", "", true, false)),
            List.of(new VolumeSpec("data", "/var/lib/postgresql/data")), null)));

    StoredSupportService stored = configOf(id).supportServices().getFirst();
    assertEquals("postgres:17-alpine", stored.image());
    assertEquals(Map.of("POSTGRES_PASSWORD", "pw"), decrypted(stored.environment()));
  }

  @Test
  void supportServicesCanStillBeAddedAndRemovedJustNotSwappedInOneSave() {
    String id = service.create(managedWith("Files", support("database", "pw"))).id();
    settle(id);

    service.update(id, managedWith("Files", support("database", ""), support("cache", "other")));
    assertEquals(List.of("database", "cache"), supportNames(id));

    settle(id);
    service.update(id, managedWith("Files"));
    assertTrue(supportNames(id).isEmpty());
  }

  @Test
  void updatingAStoppedManagedRecordRewritesItsStackStraightAway() {
    // it is stopped, so re-provisioning now is free and keeps the file in step with the record
    String id = service.create(managed("Files", "dh-local")).id();
    settle(id);
    operations.clear();

    McpServerDto updated = service.update(id, managed("Files renamed", "dh-local"));

    assertEquals("Files renamed", updated.name());
    assertEquals(2, row(id).revision());
    assertEquals("applying", row(id).operationState());
    assertEquals(1, operations.queued());
  }

  @Test
  void updatingARunningManagedRecordLeavesItOutOfDateUntilApply() {
    // restarting a live MCP server behind the operator's back would drop every Agent's
    // connection; the drift is reported instead, and apply is theirs to press
    String id = service.create(managed("Files", "dh-local")).id();
    repository.beginOperation(id, "running", "idle");
    operations.clear();

    McpServerDto updated = service.update(id, managed("Files renamed", "dh-local"));

    assertEquals(2, updated.revision());
    assertEquals(0, updated.appliedRevision());
    assertTrue(updated.pendingChanges());
    assertEquals("idle", row(id).operationState());
    assertEquals(0, operations.queued(), "nothing may be applied without an explicit apply");
  }

  @Test
  void aRecordWithAnOperationInFlightRefusesEveryFurtherMutation() {
    // the row is the single-flight lock: a second Compose run for one record races the first
    String id = service.create(managed("Files", "dh-local")).id();
    assertEquals("provisioning", row(id).operationState());

    for (Runnable mutation : List.of(
        (Runnable) () -> service.update(id, managed("Files renamed", "dh-local")),
        () -> service.start(id),
        () -> service.stop(id),
        () -> service.apply(id),
        () -> service.delete(id),
        () -> service.assertDeletable(id))) {
      assertEquals("an MCP server operation is already in progress",
          assertThrows(ResourceConflictException.class, mutation::run).getMessage());
    }
  }

  @Test
  void aFailedRecordCanStillBeMutatedBecauseErrorIsIdleWithAStory() {
    String id = service.create(managed("Files", "dh-local")).id();
    repository.beginOperation(id, "stopped", "error");

    service.assertDeletable(id);
    assertEquals(2, service.update(id, managed("Files renamed", "dh-local")).revision());
  }

  // ── container lifecycle ─────────────────────────────────────────────────

  @Test
  void startStopAndApplyRecordTheirIntentBeforeAnyComposeWork() {
    String id = service.create(managed("Files", "dh-local")).id();
    settle(id);
    operations.clear();

    service.start(id);
    assertEquals("running", row(id).desiredState());
    assertEquals("starting", row(id).operationState());

    settle(id);
    service.stop(id);
    assertEquals("stopped", row(id).desiredState());
    assertEquals("stopping", row(id).operationState());

    settle(id);
    service.apply(id);
    // apply reconciles toward whatever the desired state already is, so it must not change it
    assertEquals("stopped", row(id).desiredState());
    assertEquals("applying", row(id).operationState());
    assertEquals(3, operations.queued());
  }

  @Test
  void theContainerLifecycleDoesNotApplyToServersMissionControlDoesNotRun() {
    String external = service.create(external("Remote docs")).id();
    String stdio = service.create(stdio("Local tool")).id();

    for (String id : List.of(external, stdio)) {
      for (Runnable call : List.of(
          (Runnable) () -> service.start(id), () -> service.stop(id), () -> service.apply(id))) {
        assertEquals("container lifecycle applies only to managed MCP servers",
            assertThrows(IllegalArgumentException.class, call::run).getMessage());
      }
    }
    assertEquals(0, operations.queued());
  }

  // ── delete ──────────────────────────────────────────────────────────────

  @Test
  void anExternalRecordIsDeletedOutrightWhileAManagedOneIsQueuedForTeardown() {
    String external = service.create(external("Remote docs")).id();
    String managed = service.create(managed("Files", "dh-local")).id();
    settle(managed);
    operations.clear();

    service.delete(external);
    assertTrue(repository.findById(external).isEmpty());

    service.delete(managed);
    // the row survives until its containers and volumes are gone, carrying the operation state
    assertEquals("deleting", row(managed).operationState());
    assertEquals("stopped", row(managed).desiredState());
    assertEquals(1, operations.queued());
  }

  @Test
  void aServerStillLinkedToAnAgentCannotBeDeleted() {
    // the Agent's config.yaml would keep a connection to a server that no longer exists
    String id = service.create(external("Remote docs")).id();
    links.upsert(new AgentMcpLink("dh-local", "container", "default", "docs", id, 1, 0, 0));

    assertEquals("the MCP server is still linked to one or more Agents; disable and unlink them, then retry",
        assertThrows(ResourceConflictException.class, () -> service.delete(id)).getMessage());
    assertTrue(repository.findById(id).isPresent());
  }

  @Test
  void assertDeletableAnswersBeforeAnyAgentIsTouched() {
    // the Agent layer asks first, because disabling and unlinking every copy is not undone if
    // the delete then refuses
    String id = service.create(managed("Files", "dh-local")).id();
    settle(id);

    service.assertDeletable(id);
    assertThrows(NoSuchElementException.class, () -> service.assertDeletable("mcp-nope"));
  }

  // ── reads ───────────────────────────────────────────────────────────────

  @Test
  void theSameHostConnectionUrlIsTheServiceNameForManagedAndTheStoredUrlForExternal() {
    String managed = service.create(managed("Files", "dh-local")).id();
    String external = service.create(external("Remote docs")).id();
    String stdio = service.create(stdio("Local tool")).id();

    assertEquals("http://" + row(managed).serviceKey() + ":1100/mcp",
        service.sameHostConnectionUrl(managed));
    assertEquals("https://example.test/mcp", service.sameHostConnectionUrl(external));
    // a stdio server is not reachable over the network at all
    assertNull(service.sameHostConnectionUrl(stdio));
  }

  @Test
  void logsAreRefusedForARecordThatHasNoContainer() {
    String id = service.create(external("Remote docs")).id();

    assertEquals("logs are available only for managed MCP servers",
        assertThrows(IllegalArgumentException.class, () -> service.logs(id, 100)).getMessage());
  }

  @Test
  void aReachabilityCheckIsRefusedForStdio() {
    String id = service.create(stdio("Local tool")).id();

    assertEquals("reachability checks do not apply to stdio MCP servers",
        assertThrows(IllegalArgumentException.class, () -> service.check(id)).getMessage());
  }

  @Test
  void anUnknownIdIsANotFoundOnEveryEndpointThatTakesOne() {
    for (Runnable call : List.of(
        (Runnable) () -> service.definition("mcp-nope"),
        () -> service.live("mcp-nope"),
        () -> service.update("mcp-nope", external("Remote docs")),
        () -> service.delete("mcp-nope"),
        () -> service.start("mcp-nope"),
        () -> service.stop("mcp-nope"),
        () -> service.apply("mcp-nope"),
        () -> service.check("mcp-nope"),
        () -> service.logs("mcp-nope", 100),
        () -> service.materializedEnvironment("mcp-nope"),
        () -> service.materializedHeaders("mcp-nope"),
        () -> service.sameHostConnectionUrl("mcp-nope"))) {
      assertEquals("unknown MCP server: mcp-nope",
          assertThrows(NoSuchElementException.class, call::run).getMessage());
    }
  }

  @Test
  void aStdioEnvironmentIsDecryptedOnlyOnThisSideOfTheApi() {
    String id = service.create(stdio("Local tool")).id();

    assertEquals(Map.of("TOKEN", "super-secret"), service.materializedEnvironment(id));
    // and never in the row or the DTO
    assertTrue(row(id).configJson().contains("enc:v1:"));
    assertNull(service.definition(id).environment().getFirst().value());
  }

  // ── retained volumes ────────────────────────────────────────────────────

  @Test
  void aRetainedVolumeIsListedReadableAndPurgedThroughCompose() {
    // deleting a managed server keeps its named volumes: the data outlives the record until an
    // operator says otherwise
    retained.retain("mcp-gone", "Files", "dh-local", "mission-control-mcp-files-data");
    RetainedResourceDto resource = service.retainedResources().getFirst();

    assertEquals("mission-control-mcp-files-data", service.retainedResource(resource.id()).name());

    service.purgeRetainedResource(resource.id());

    verify(compose).purgeVolume("dh-local", "mission-control-mcp-files-data");
    assertTrue(service.retainedResources().isEmpty());
    assertThrows(NoSuchElementException.class, () -> service.retainedResource(resource.id()));
  }

  // ── startup ─────────────────────────────────────────────────────────────

  @Test
  void startupSeedsOnceAndThenReconcilesEveryManagedRecord() {
    graph.startup().onApplicationReady();
    int afterFirst = operations.queued();
    long seeded = repository.findAll().size();

    graph.startup().onApplicationReady();

    // seeding the local host row is HostService's own ordered listener, not this one's business:
    // it used to be called from here to force an ordering that @Order now states outright
    verifyNoInteractions(hosts);
    assertEquals(seeded, repository.findAll().size(), "defaults are seeded once, not on every boot");
    // every managed record is reconciled on each boot: the daemon may have been restarted under us
    assertTrue(afterFirst > 0);
    assertEquals(afterFirst * 2, operations.queued());
    for (ServerRow row : repository.findAll()) {
      if ("managed".equals(row.kind())) assertEquals("reconciling", row.operationState());
    }
  }

  @Test
  void aRecordCaughtMidDeletionByARestartResumesItsDeletionRatherThanBeingReconciled() {
    String id = service.create(managed("Files", "dh-local")).id();
    repository.beginOperation(id, "stopped", "deleting");
    operations.clear();

    graph.startup().onApplicationReady();

    // the row stays 'deleting': a reconcile here would re-provision a server the operator
    // already asked to remove
    assertEquals("deleting", row(id).operationState());
    assertTrue(operations.queued() > 0);
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private ServerRow row(String id) {
    return repository.findById(id).orElseThrow();
  }

  /** Marks the record's queued operation as finished, without doing it. */
  private void settle(String id) {
    repository.beginOperation(id, row(id).desiredState(), "idle");
  }

  /** A managed record on {@code dh-local} carrying the given support services. */
  private static McpServerRequest managedWith(String name, SupportServiceRequest... supports) {
    return new McpServerRequest(name, "desc", "managed", "dh-local", "http", null,
        "example/files:1", null, List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of(supports));
  }

  /** A dependency with a secret and a volume — the two things a rename would strand. */
  private static SupportServiceRequest support(String name, String password) {
    return new SupportServiceRequest(name, "postgres:16-alpine", null, List.of(), List.of(),
        List.of(new ConfigValueInput("POSTGRES_PASSWORD", password, true, false)),
        List.of(new VolumeSpec("data", "/var/lib/postgresql/data")), null);
  }

  private List<String> supportNames(String id) {
    return configOf(id).supportServices().stream().map(StoredSupportService::name).toList();
  }

  private StoredConfig configOf(String id) {
    try {
      return new ObjectMapper().readValue(row(id).configJson(), StoredConfig.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Its own store, because {@link McpWiring#cipher()} is the key both were sealed under and
   *  the graph does not expose the one it built. */
  private static Map<String, String> decrypted(List<StoredValue> values) {
    return new McpConfigStore(new SecretsAtRest(McpWiring.cipher()), new ObjectMapper())
        .materialize(values);
  }

  private static McpServerRequest managed(String name, String hostId) {
    return new McpServerRequest(name, "desc", "managed", hostId, "http", null,
        "example/files:1", null, List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null, List.of());
  }

  private static McpServerRequest external(String name) {
    return new McpServerRequest(name, "desc", "external", null, "http", "https://example.test/mcp",
        null, null, List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of());
  }

  private static McpServerRequest stdio(String name) {
    return new McpServerRequest(name, "desc", "stdio", null, null, null, null, null,
        List.of(), List.of(), "npx", List.of("-y", "@example/tool"), null, null, null, null,
        List.of(new ConfigValueInput("TOKEN", "super-secret", true, false)),
        List.of(), List.of(), null, List.of());
  }

  /**
   * Records the operations the service hands off instead of running them. The Compose work needs
   * a daemon; the rules that decide whether it is queued at all do not.
   */
  private static final class QueuedOperations extends AbstractExecutorService {
    private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean shutdown;

    int queued() {
      return tasks.size();
    }

    void clear() {
      tasks.clear();
    }

    /** Runs everything recorded so far, in order, the way the real executor eventually would. */
    void runAll() {
      List<Runnable> pending = List.copyOf(tasks);
      tasks.clear();
      pending.forEach(Runnable::run);
    }

    @Override public void execute(Runnable command) {
      tasks.add(command);
    }

    @Override public void shutdown() {
      shutdown = true;
    }

    @Override public List<Runnable> shutdownNow() {
      shutdown = true;
      return List.of();
    }

    @Override public boolean isShutdown() {
      return shutdown;
    }

    @Override public boolean isTerminated() {
      return shutdown;
    }

    @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
      return true;
    }
  }

  // ── the queued work, once it runs ────────────────────────────────────────

  @Test
  void aQueuedProvisionRunsTheStackAndClearsTheOperation() {
    String id = service.create(managed("Files", "dh-local")).id();

    operations.runAll();

    ServerRow row = row(id);
    assertEquals("idle", row.operationState());
    assertEquals(row.revision(), row.appliedRevision());
    verify(compose).execute(eq("dh-local"), any(), any(), any());
  }

  @Test
  void aQueuedStartAndStopEachReachTheirComposeCommand() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();

    service.start(id);
    operations.runAll();
    assertEquals("running", row(id).runtimeState());

    service.stop(id);
    operations.runAll();
    assertEquals("stopped", row(id).runtimeState());
  }

  @Test
  void aQueuedApplyReconcilesTowardWhicheverStateIsRecorded() {
    // apply is the same operation for a stopped and a running record; only the target differs
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();

    service.apply(id);
    operations.runAll();
    assertEquals("stopped", row(id).runtimeState(), "a stopped record is re-provisioned stopped");

    service.start(id);
    operations.runAll();
    service.apply(id);
    operations.runAll();
    assertEquals("running", row(id).runtimeState(), "a running record is brought back up");
  }

  @Test
  void aQueuedDeleteRemovesTheRecordOnceItsStackIsTornDown() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();
    service.delete(id);

    operations.runAll();

    assertTrue(repository.findById(id).isEmpty());
  }

  @Test
  void startupReconciliationRunsForEveryManagedRecord() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();
    graph.startup().onApplicationReady();

    operations.runAll();

    assertEquals("idle", row(id).operationState());
    assertTrue(repository.findAll().size() > 1, "the seeded defaults are there too");
  }

  @Test
  void aRecordResumingItsDeletionAfterARestartIsActuallyDeleted() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();
    repository.beginOperation(id, "stopped", "deleting");
    operations.clear();
    graph.startup().onApplicationReady();

    operations.runAll();

    assertTrue(repository.findById(id).isEmpty());
  }

  @Test
  void aCatalogReadRefreshesRuntimeStateAndAnUnknownServerIsStillANotFound() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();
    when(compose.serviceContainerId(eq("dh-local"), anyString())).thenReturn(null);

    assertTrue(service.list().stream().anyMatch(dto -> id.equals(dto.id())));
    assertEquals("missing", service.live(id).runtimeState());
    assertThrows(NoSuchElementException.class, () -> service.live("mcp-nope"));
  }

  /**
   * The Agent listing reads catalog rows once per linked entry per profile, on a poll. Every
   * one of those reads used to run the refresh above — a {@code docker compose ps} taken under
   * the host's compose lock, plus a full container listing — for a revision number.
   */
  @Test
  void readingADefinitionContactsNoDaemonAndWritesNothing() {
    String id = service.create(managed("Files", "dh-local")).id();
    operations.runAll();
    ServerRow before = row(id);
    clearInvocations(compose);

    assertEquals(id, service.definition(id).id());

    verifyNoInteractions(compose);
    assertEquals(before, row(id));
  }
}
