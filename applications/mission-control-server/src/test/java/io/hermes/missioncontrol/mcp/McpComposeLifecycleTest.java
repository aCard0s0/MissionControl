package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Compose work itself: the arguments each operation runs, and what it records afterwards.
 *
 * <p>{@link McpRegistryLifecycleTest} covers the rules that decide whether an operation is
 * queued; this covers what happens when one runs. Two properties carry the weight — a stop must
 * not claim a pending definition was applied, and a delete must retain the record's named volumes
 * before the record that names them is gone, because nothing else remembers where the data came
 * from. The Docker CLI is substituted; everything else is real, including the schema.
 */
class McpComposeLifecycleTest {

  private static final String HOST = "dh-local";
  private static final String SERVICE = "mcp-files";

  private SqliteTestDatabase database;
  private McpServerRepository repository;
  private RetainedResourceRepository retained;
  private McpConfigStore configs;
  private ComposeStackManager compose;
  private DockerGateway docker;
  private HostService hosts;
  private McpComposeLifecycle lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    JdbcTemplate jdbc = database.jdbc();
    repository = new McpServerRepository(jdbc);
    retained = new RetainedResourceRepository(jdbc);
    configs = new McpConfigStore(new SecretsAtRest(new SecretCipher("test-secret", "", false)), new ObjectMapper());
    compose = mock(ComposeStackManager.class);
    docker = mock(DockerGateway.class);
    hosts = mock(HostService.class);
    when(hosts.ref(HOST)).thenReturn(new DockerHostRef(HOST, "unix:///sock"));
    lifecycle = new McpComposeLifecycle(repository, retained, hosts, docker, compose,
        new ComposeStackRenderer(), configs, Executors.newSingleThreadExecutor());
  }

  @AfterEach
  void tearDown() throws Exception {
    lifecycle.shutdown();
    database.close();
  }

  // ── provision and start ─────────────────────────────────────────────────

  @Test
  void provisioningCreatesTheContainerWithoutStartingItAndMarksTheRevisionApplied() {
    // a new record is provisioned stopped so the image is pulled and the container exists before
    // the operator ever presses start
    String id = insertManaged("Files");

    lifecycle.provisionStopped(id);

    assertEquals(List.of("up", "--no-start", "--pull", "always", "--force-recreate", SERVICE),
        composeArguments());
    assertEquals(Duration.ofMinutes(10), composeTimeout());
    ServerRow row = row(id);
    assertEquals("stopped", row.runtimeState());
    assertEquals("idle", row.operationState());
    assertEquals(row.revision(), row.appliedRevision(), "the stack now matches the definition");
    assertNull(row.operationError());
  }

  @Test
  void startingPullsAndDetachesAndOnlyForcesRecreationWhenAsked() {
    String id = insertManaged("Files");

    lifecycle.runStart(id, false);
    assertEquals(List.of("up", "--detach", "--pull", "always", SERVICE), composeArguments());

    lifecycle.runStart(id, true);
    // a reconcile after a definition change has to replace the container, not reuse it
    assertEquals(List.of("up", "--detach", "--pull", "always", "--force-recreate", SERVICE),
        composeArguments());
    assertEquals("running", row(id).runtimeState());
    assertEquals(row(id).revision(), row(id).appliedRevision());
  }

  // ── reclaiming departed dependencies ────────────────────────────────────

  @Test
  void aDependencyDroppedFromTheDefinitionHasItsContainerRemovedAndItsVolumeRetained() {
    // the file no longer names the service, so Compose is never asked to bring it down: without
    // this it keeps running under a service name nothing references, holding data nothing
    // remembers the origin of
    String id = insertManaged("Files");
    String departedVolume = "mission-control-mcp-" + SERVICE + "-database-data";
    when(compose.servicesOf(HOST, id)).thenReturn(List.of(SERVICE, SERVICE + "-database"));
    when(compose.volumesOf(HOST, id)).thenReturn(List.of(departedVolume));

    lifecycle.runStart(id, false);

    verify(compose).removeServices(HOST, id, List.of(SERVICE + "-database"), Duration.ofMinutes(2));
    RetainedResourceDto kept = retained.findAll().getFirst();
    assertEquals(departedVolume, kept.name());
    assertEquals("Files", kept.serverName(), "the retained row carries the name the operator knew");
    assertEquals(HOST, kept.hostId());
    assertEquals("running", row(id).runtimeState(), "the start itself still succeeded");
  }

  @Test
  void aDependencyStillInTheDefinitionIsLeftRunningAndItsVolumeIsNotRetained() {
    String id = insertManagedWithDatabase("Files");
    ComposeStackRenderer.Rendered stack = lifecycle.renderHost(HOST);
    when(compose.servicesOf(HOST, id)).thenReturn(stack.serviceNames().get(id));
    when(compose.volumesOf(HOST, id)).thenReturn(stack.volumeNames().get(id));

    lifecycle.runStart(id, false);

    verify(compose).removeServices(HOST, id, List.of(), Duration.ofMinutes(2));
    assertTrue(retained.findAll().isEmpty(), "a declared volume is part of the stack, not stranded");
  }

  @Test
  void aVolumeAlreadyWaitingToBePurgedIsNotRetainedTwiceByTheNextStart() {
    // it stays stranded until the operator purges it, so every later start finds it again
    String id = insertManaged("Files");
    String departedVolume = "mission-control-mcp-" + SERVICE + "-database-data";
    when(compose.volumesOf(HOST, id)).thenReturn(List.of(departedVolume));

    lifecycle.runStart(id, false);
    lifecycle.runStart(id, false);

    assertEquals(1, retained.findAll().size());
  }

  @Test
  void provisioningReclaimsDepartedDependenciesToo() {
    // an update to a stopped record re-provisions straight away, which is where a dependency
    // dropped by that same update has to be reclaimed
    String id = insertManaged("Files");
    when(compose.servicesOf(HOST, id)).thenReturn(List.of(SERVICE, SERVICE + "-cache"));

    lifecycle.provisionStopped(id);

    verify(compose).removeServices(HOST, id, List.of(SERVICE + "-cache"), Duration.ofMinutes(2));
  }

  @Test
  void aReclaimThatCannotRunDoesNotReportAStartThatWorkedAsAFailure() {
    // the container is already up by then; recording an error would have the operator retry a
    // start that succeeded
    String id = insertManaged("Files");
    when(compose.servicesOf(HOST, id))
        .thenThrow(new UpstreamUnavailableException("could not start Docker CLI"));

    lifecycle.runStart(id, false);

    ServerRow row = row(id);
    assertEquals("running", row.runtimeState());
    assertEquals("idle", row.operationState());
    assertNull(row.operationError());
  }

  // ── stop ────────────────────────────────────────────────────────────────

  @Test
  void stoppingGivesTheContainerAGraceTimeoutAndDoesNotClaimTheDefinitionWasApplied() {
    // the record is one revision ahead of its stack; stopping it does not write that stack, so
    // marking it applied would hide a pending change the operator still has to apply
    String id = insertManaged("Files");
    repository.updateDefinition(id, "Files", null, row(id).configJson(), 2, 1, "stopping",
        row(id).revision());

    lifecycle.runStop(id);

    assertEquals(List.of("stop", "--timeout", "10", SERVICE), composeArguments());
    assertEquals(Duration.ofMinutes(2), composeTimeout());
    ServerRow row = row(id);
    assertEquals("stopped", row.runtimeState());
    assertEquals("idle", row.operationState());
    assertEquals(2, row.revision());
    assertEquals(1, row.appliedRevision(), "a stop must leave the pending change pending");
  }

  // ── delete ──────────────────────────────────────────────────────────────

  @Test
  void deletingRetainsTheRecordsNamedVolumesBeforeTheRecordThatNamesThemIsGone() {
    // the volume outlives the record on purpose: an operator who deletes a database MCP server
    // has not asked to lose its data, and nothing else remembers which server a volume came from
    String id = insertManaged("Files", volume());
    String volumeName = lifecycle.renderHost(HOST).volumeNames().get(id).getFirst();

    lifecycle.runDelete(id);

    assertEquals(List.of("rm", "--stop", "--force", SERVICE), composeArguments());
    assertEquals(Duration.ofMinutes(3), composeTimeout());
    assertTrue(repository.findById(id).isEmpty());
    RetainedResourceDto kept = retained.findAll().getFirst();
    assertEquals(volumeName, kept.name());
    assertEquals("Files", kept.serverName(), "the retained row carries the name the operator knew");
    assertEquals(HOST, kept.hostId());
  }

  @Test
  void deletingRewritesTheHostStackWithoutTheDeletedRecord() {
    // Compose owns whole files: the remaining records' stack has to be rewritten, or the next
    // operation on this host would re-create the service just removed
    String kept = insertManaged("Keeper");
    String removed = insertManaged("Doomed");

    lifecycle.runDelete(removed);

    InOrder order = inOrder(compose);
    order.verify(compose).execute(anyString(), any(), any(), any());
    ArgumentCaptor<ComposeStackRenderer.Rendered> rewritten =
        ArgumentCaptor.forClass(ComposeStackRenderer.Rendered.class);
    order.verify(compose).writeOnly(anyString(), rewritten.capture());
    assertEquals(List.of(kept), List.copyOf(rewritten.getValue().serviceNames().keySet()));
  }

  @Test
  void aDeleteThatFailsLeavesTheRecordBehindInErrorSoItCanBeRetried() {
    String id = insertManaged("Files");
    when(compose.execute(anyString(), any(), any(), any()))
        .thenThrow(new UpstreamUnavailableException("Docker Compose operation failed: container in use"));

    lifecycle.runDelete(id);

    ServerRow row = row(id);
    assertEquals("error", row.operationState());
    assertTrue(row.operationError().contains("container in use"));
    assertTrue(retained.findAll().isEmpty(), "nothing is retained for a teardown that did not run");
  }

  // ── failure recording ───────────────────────────────────────────────────

  @Test
  void aFailedComposeRunIsRecordedAgainstTheRecordWithTheDaemonsOwnReason() {
    // the operator's only evidence is the row: an operation that fails silently reads as one
    // still in progress, forever
    String id = insertManaged("Files");
    when(compose.execute(anyString(), any(), any(), any()))
        .thenThrow(new UpstreamUnavailableException("Docker Compose operation failed: no such image"));

    lifecycle.provisionStopped(id);

    ServerRow row = row(id);
    assertEquals("error", row.runtimeState());
    assertEquals("error", row.operationState());
    assertTrue(row.operationError().contains("no such image"));
    assertEquals(0, row.appliedRevision(), "a failed run applied nothing");
  }

  @Test
  void anUnrecoverableSecretStopsTheRunBeforeAnythingIsExecuted() {
    // rendering would write an empty variable into the Compose file and the server would come up
    // unauthenticated; refusing is the safe outcome
    String id = insertManagedWithUndecryptableSecret();

    lifecycle.provisionStopped(id);
    lifecycle.runStart(id, false);

    assertTrue(row(id).operationError().contains("secret value is unrecoverable: TOKEN"),
        row(id).operationError());
    verify(compose, never()).execute(anyString(), any(), any(), any());
  }

  @Test
  void aRecordWithABrokenSecretCanStillBeStoppedAndDeleted() {
    // the guard protects starting something misconfigured; it must not trap the record in a state
    // the operator cannot get out of
    String id = insertManagedWithUndecryptableSecret();

    lifecycle.runStop(id);
    assertEquals("stopped", row(id).runtimeState());

    lifecycle.runDelete(id);
    assertTrue(repository.findById(id).isEmpty());
  }

  @Test
  void anOperationThrowingOutsideItsOwnHandlingIsStillRecorded() throws Exception {
    String id = insertManaged("Files");

    lifecycle.submit(id, () -> {
      throw new IllegalStateException("boom");
    });
    Thread.sleep(200);

    assertEquals("error", row(id).operationState());
    assertTrue(row(id).operationError().contains("boom"));
  }

  @Test
  void aFailureForARecordThatIsAlreadyGoneIsDroppedRatherThanResurrectingIt() throws Exception {
    // runDelete removes the row and then rewrites the stack; if that rewrite fails there is no
    // row left to record it against, and inserting one would bring back a deleted server
    lifecycle.submit("mcp-already-deleted", () -> {
      throw new IllegalStateException("stack rewrite failed");
    });
    Thread.sleep(200);

    assertTrue(repository.findAll().isEmpty());
  }

  // ── runtime refresh ─────────────────────────────────────────────────────

  @Test
  void theRuntimeStateIsRefreshedFromWhatTheDaemonActuallyReports() {
    String id = insertIdleManaged("Files");
    when(compose.serviceContainerId(HOST, SERVICE)).thenReturn("cid");
    when(docker.listContainers(new DockerHostRef(HOST, "unix:///sock"), true))
        .thenReturn(List.of(container("cid", "running")));

    assertEquals("running", lifecycle.refreshRuntime(row(id)).runtimeState());
    assertEquals("running", row(id).runtimeState(), "the refreshed state is persisted");
  }

  @Test
  void anUnhealthyContainerIsRecordedAsAnErrorBecauseThatIsWhatItIsForAnMcpServer() {
    String id = insertIdleManaged("Files");
    when(compose.serviceContainerId(HOST, SERVICE)).thenReturn("cid");
    when(docker.listContainers(new DockerHostRef(HOST, "unix:///sock"), true))
        .thenReturn(List.of(container("cid", "unhealthy")));

    assertEquals("error", lifecycle.refreshRuntime(row(id)).runtimeState());
  }

  @Test
  void aContainerTheDaemonNoLongerListsIsUnknownAndNoContainerAtAllIsMissing() {
    String id = insertIdleManaged("Files");
    when(compose.serviceContainerId(HOST, SERVICE)).thenReturn("cid");
    when(docker.listContainers(new DockerHostRef(HOST, "unix:///sock"), true))
        .thenReturn(List.of(container("someone-else", "running")));
    assertEquals("unknown", lifecycle.refreshRuntime(row(id)).runtimeState());

    when(compose.serviceContainerId(HOST, SERVICE)).thenReturn(null);
    assertEquals("missing", lifecycle.refreshRuntime(row(id)).runtimeState());
  }

  @Test
  void aRecordMidOperationIsLeftAloneSoAReadCannotRaceItsComposeRun() {
    // provisioning/starting/stopping all have a Compose run writing this row; reading over it
    // would overwrite the state that run is about to record
    String id = insertManaged("Files");
    assertEquals("provisioning", row(id).operationState());

    assertEquals("provisioning", lifecycle.refreshRuntime(row(id)).operationState());

    verify(compose, never()).serviceContainerId(anyString(), anyString());
  }

  @Test
  void aDeadDaemonDoesNotMakeACatalogReadFail() {
    // inventory is best-effort: the lifecycle records its own failures, and a GET on the catalog
    // must not turn into a 503 because one host is down
    String id = insertIdleManaged("Files");
    when(compose.serviceContainerId(HOST, SERVICE))
        .thenThrow(new UpstreamUnavailableException("could not start Docker CLI"));

    assertEquals("missing", lifecycle.refreshRuntime(row(id)).runtimeState());
  }

  @Test
  void anExternalRecordIsNeverAskedAboutAContainer() {
    ServerRow external = new ServerRow("mcp-ext", "Remote", null, "external", HOST, null, "{}",
        "stopped", "unavailable", "idle", null, 1, 1, null, null, null, null, null, 0L, 0L);

    assertEquals("unavailable", lifecycle.refreshRuntime(external).runtimeState());
    verify(compose, never()).serviceContainerId(anyString(), anyString());
  }

  // ── rendering ───────────────────────────────────────────────────────────

  @Test
  void aHostStackCarriesEveryManagedRecordOnThatHostAndNothingElse() {
    // Compose owns whole files, so one record's change is written as part of the host's complete
    // stack — leaving a sibling out would tear it down
    String first = insertManaged("Files");
    String second = insertManaged("Docs");
    insertExternal("Remote docs");
    insertManagedOnHost("Elsewhere", "dh-remote");

    ComposeStackRenderer.Rendered stack = lifecycle.renderHost(HOST);

    // findByHost orders by name, so compare as a set: which records are in the stack is the point
    assertEquals(Set.of(first, second), stack.serviceNames().keySet());
  }

  @Test
  void aRenderedStackCarriesTheDecryptedEnvironmentOutOfBandNotInTheFile() {
    String id = insertManaged("Files", List.of(), List.of(
        new ConfigValueInput("TOKEN", "super-secret", true, false)));

    ComposeStackRenderer.Rendered stack = lifecycle.renderHost(HOST);

    assertTrue(stack.processEnvironment().containsValue("super-secret"),
        "Compose reads the secret from its process environment");
    assertTrue(stack.yaml().contains("TOKEN"));
    assertTrue(!stack.yaml().contains("super-secret"), "and never from the file on disk");
    assertEquals(List.of(id), List.copyOf(stack.serviceNames().keySet()));
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private ServerRow row(String id) {
    return repository.findById(id).orElseThrow();
  }

  private List<String> composeArguments() {
    ArgumentCaptor<List<String>> arguments = ArgumentCaptor.forClass(List.class);
    verify(compose, org.mockito.Mockito.atLeastOnce())
        .execute(anyString(), any(), arguments.capture(), any());
    return arguments.getValue();
  }

  private Duration composeTimeout() {
    ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
    verify(compose, org.mockito.Mockito.atLeastOnce())
        .execute(anyString(), any(), any(), timeout.capture());
    return timeout.getValue();
  }

  private static ContainerDto container(String id, String status) {
    return new ContainerDto(id, id.substring(0, Math.min(6, id.length())), "mc-mcp-files", HOST,
        status, "example/files:1", null, null, null, null, List.of());
  }

  private String insertManaged(String name) {
    return insertManaged(name, List.of(), List.of());
  }

  private String insertManaged(String name, List<VolumeSpec> volumes) {
    return insertManaged(name, volumes, List.of());
  }

  private String insertManaged(String name, List<VolumeSpec> volumes, List<ConfigValueInput> environment) {
    return insert(name, "managed", HOST, volumes, environment, "provisioning");
  }

  /** A managed record whose one dependency declares a named volume of its own. */
  private String insertManagedWithDatabase(String name) {
    Validated validated = McpRequestValidator.validate(new McpServerRequest(
        name, null, "managed", HOST, "http", null, "example/files:1", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(), null,
        List.of(new SupportServiceRequest("database", "postgres:16-alpine", null,
            List.of(), List.of(), List.of(), volume(), null))));
    return insertRow(name, "managed", HOST,
        configs.write(configs.store(validated, null)), "provisioning");
  }

  private String insertIdleManaged(String name) {
    return insert(name, "managed", HOST, List.of(), List.of(), "idle");
  }

  private String insertManagedOnHost(String name, String hostId) {
    return insert(name, "managed", hostId, List.of(), List.of(), "provisioning");
  }

  private String insertExternal(String name) {
    Validated validated = McpRequestValidator.validate(new McpServerRequest(
        name, null, "external", null, "http", "https://example.test/mcp", null, null,
        List.of(), List.of(), null, List.of(), null, null, null, null,
        List.of(), List.of(), List.of(), null, List.of()));
    return insertRow(name, "external", null, configs.write(configs.store(validated, null)), "idle");
  }

  private String insert(
      String name, String kind, String hostId, List<VolumeSpec> volumes,
      List<ConfigValueInput> environment, String operationState) {
    Validated validated = McpRequestValidator.validate(new McpServerRequest(
        name, null, kind, hostId, "http", null, "example/files:1", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        environment, List.of(), volumes, null, List.of()));
    return insertRow(name, kind, hostId, configs.write(configs.store(validated, null)), operationState);
  }

  /** A record whose secret was encrypted with a key this store does not have. */
  private String insertManagedWithUndecryptableSecret() {
    McpConfigStore otherKey =
        new McpConfigStore(new SecretsAtRest(new SecretCipher("a-different-secret", "", false)), new ObjectMapper());
    Validated validated = McpRequestValidator.validate(new McpServerRequest(
        "Files", null, "managed", HOST, "http", null, "example/files:1", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(new ConfigValueInput("TOKEN", "super-secret", true, false)),
        List.of(), List.of(), null, List.of()));
    return insertRow("Files", "managed", HOST,
        otherKey.write(otherKey.store(validated, null)), "provisioning");
  }

  private String insertRow(
      String name, String kind, String hostId, String configJson, String operationState) {
    // the service key is unique per host, so it follows the id — 'Files' yields SERVICE
    String id = "mcp-" + name.toLowerCase().replace(' ', '-');
    repository.insert(new ServerRow(id, name, null, kind, hostId,
        "managed".equals(kind) ? id : null, configJson,
        "stopped", "managed".equals(kind) ? "missing" : "unavailable", operationState, null,
        1, 0, null, null, null, null, null, 0L, 0L));
    return id;
  }

  private static List<VolumeSpec> volume() {
    return List.of(new VolumeSpec("data", "/data"));
  }
}
