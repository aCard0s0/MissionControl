package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The managed MCP lifecycle against a real Docker daemon.
 *
 * <p>Every other test of this code substitutes {@code ComposeStackManager.run}, which pins the
 * argv Mission Control builds but never asks Docker whether it accepts it. If
 * {@code compose rm --stop --force} had a wrong flag, or {@code --pull always} were unsupported by
 * the installed Compose, the whole mocked suite would still pass. This is the only test that can
 * tell. It also proves the ownership guard's format string
 * ({@code {{ index .Labels "io.hermes.mission-control.owner" }}}) against real
 * {@code docker inspect} output, including what an unlabelled resource actually returns.
 *
 * <p><b>Opt-in.</b> Tagged {@code docker} and excluded from {@code mvn test}. Run it with
 * {@code mvn test -Dgroups=docker -Dsurefire.excludedGroups= -Djacoco.skip=true}.
 *
 * <p><b>Why it is safe to run on a machine that also runs Mission Control.</b> The Compose project
 * name is a production constant, so this cannot isolate itself by project — instead it never names
 * anything it did not create. The service key carries a random suffix, so the container, the
 * volume and the Compose file all belong to this run alone; the stack file is written to a
 * temporary directory; the record lives in a throwaway in-memory database, so the host render
 * describes only this one service. It never runs {@code compose down} and never passes
 * {@code --remove-orphans} — both of which would take a real deployment's services with them. The
 * shared {@code mission-control-mcp-net} network is reused if present and is never removed.
 */
@Tag("docker")
class ComposeStackDockerAcceptanceTest {

  private static final String HOST = "dh-acceptance";
  private static final String SOCKET = "unix:///var/run/docker.sock";
  /** Small, quick to pull, and stays up while sleeping. */
  private static final String IMAGE = "busybox:1.36";

  @TempDir
  Path stackDirectory;

  private String serviceKey;
  private String volumeName;
  private SqliteTestDatabase database;
  private McpServerRepository repository;
  private RetainedResourceRepository retained;
  private ComposeStackManager compose;
  private McpComposeLifecycle lifecycle;

  @BeforeEach
  void setUp() throws Exception {
    assumeTrue(commandSucceeds("docker", "version"), "no reachable Docker daemon");
    assumeTrue(commandSucceeds("docker", "compose", "version"), "no docker compose plugin");

    serviceKey = "mcp-it-" + UUID.randomUUID().toString().substring(0, 8);
    volumeName = ComposeStackRenderer.actualVolumeName(serviceKey + "-data");

    database = SqliteTestDatabase.open();
    JdbcTemplate jdbc = database.jdbc();
    repository = new McpServerRepository(jdbc);
    retained = new RetainedResourceRepository(jdbc);

    HostService hosts = mock(HostService.class);
    when(hosts.ref(anyString())).thenReturn(new DockerHostRef("dh-local", SOCKET));
    McpConfigStore configs =
        new McpConfigStore(new SecretsAtRest(new SecretCipher("acceptance-secret", "", false)), new ObjectMapper());
    compose = new ComposeStackManager(hosts, stackDirectory.toString());
    lifecycle = new McpComposeLifecycle(repository, retained, hosts, mock(
        io.hermes.missioncontrol.docker.DockerGateway.class), compose, new ComposeStackRenderer(),
        configs, Executors.newSingleThreadExecutor());

    repository.insert(managedRow(configs));
  }

  @AfterEach
  void tearDown() throws Exception {
    // never `compose down`: this project name is shared with a real deployment. Remove only what
    // this run created, and do it even when the test failed halfway through.
    if (serviceKey != null) {
      run("docker", "rm", "--force", "--volumes", containerId() == null ? serviceKey : containerId());
      run("docker", "volume", "rm", "--force", volumeName);
    }
    if (lifecycle != null) lifecycle.shutdown();
    if (database != null) database.close();
  }

  @Test
  void theWholeManagedLifecycleIsAcceptedByTheDaemon() throws Exception {
    // this flow alone needs a registry, because the code under test always passes --pull always.
    // On macOS a failure here is usually a locked keychain blocking the credential helper:
    // security -v unlock-keychain ~/Library/Keychains/login.keychain-db
    assumeTrue(commandSucceeds("docker", "pull", IMAGE), "cannot pull " + IMAGE);

    // 1. provision stopped: the container exists and is not running, so the operator sees an
    //    image already pulled and a container ready before they press start
    lifecycle.provisionStopped("mcp-it");
    assertEquals("idle", row().operationState(), failureOf());
    assertTrue(containerId() != null, "no container was created");
    assertFalse(isRunning(), "provisioning must not start it");
    assertTrue(volumeExists(), "the named volume is declared by the stack");

    // 2. start
    lifecycle.runStart("mcp-it", false);
    assertEquals("idle", row().operationState(), failureOf());
    assertTrue(isRunning(), "the service did not come up");

    // 2a. the batched lookup the catalog listing reads runtime state through. Everything else
    //     substitutes the CLI, so this is the only thing that can say whether the daemon
    //     accepts the --format template and prints the tab-separated pair it is asked for
    assertEquals(containerId(), compose.containerIdsByService(HOST).get(serviceKey),
        "the batched service lookup did not find the container compose ps reports");

    // 3. stop, with the grace timeout the code passes
    lifecycle.runStop("mcp-it");
    assertEquals("idle", row().operationState(), failureOf());
    assertFalse(isRunning());

    // 4. delete: the container goes, the volume is kept, and the record is gone
    lifecycle.runDelete("mcp-it");
    assertTrue(repository.findById("mcp-it").isEmpty(), "the record survived its deletion");
    assertTrue(containerId() == null, "the container survived its deletion");
    assertTrue(volumeExists(), "an operator deleting a server has not asked to lose its data");
    RetainedResourceDto kept = retained.findAll().stream()
        .filter(resource -> volumeName.equals(resource.name()))
        .findFirst().orElseThrow(() -> new AssertionError("the volume was not retained: "
            + retained.findAll()));
    assertEquals(HOST, kept.hostId());

    // 5. purge: only now is the data gone
    compose.purgeVolume(HOST, volumeName);
    assertFalse(volumeExists(), "purging left the volume behind");
  }

  @Test
  void aVolumeThatIsNotOursIsRefusedRatherThanAdopted() throws Exception {
    // the guard exists so a name collision with an unrelated stack is an error instead of Mission
    // Control mounting — and later deleting — somebody else's data. Its label lookup is a
    // `docker volume inspect --format` string, so only a real daemon proves it reads correctly.
    run("docker", "volume", "create", volumeName);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> compose.execute(HOST, lifecycle.renderHost(HOST),
            List.of("up", "--no-start", serviceKey), java.time.Duration.ofMinutes(2)));

    assertTrue(refused.getMessage().contains("not owned by Mission Control MCP"), refused.getMessage());
    assertTrue(containerId() == null, "nothing may be created once ownership fails");
  }

  @Test
  void purgingRefusesAVolumeTheDaemonSaysIsNotOurs() throws Exception {
    run("docker", "volume", "create", volumeName);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> compose.purgeVolume(HOST, volumeName));

    assertTrue(refused.getMessage().contains("not labeled"), refused.getMessage());
    assertTrue(volumeExists(), "a volume we do not own must survive the refusal");
  }

  // ── fixtures and daemon queries (deliberately not the code under test) ──

  private ServerRow managedRow(McpConfigStore configs) {
    Validated validated = McpRequestValidator.validate(new McpServerRequest(
        "Acceptance " + serviceKey, null, "managed", HOST, "http", null, IMAGE, null,
        List.of(), List.of("sh", "-c", "sleep 600"), null, List.of(), 1100, null, "/mcp", null,
        List.of(), List.of(), List.of(new VolumeSpec("data", "/data")), null, List.of()));
    long now = System.currentTimeMillis();
    return new ServerRow("mcp-it", "Acceptance " + serviceKey, null, "managed", HOST, serviceKey,
        configs.write(configs.store(validated, null)), "stopped", "missing", "provisioning", null,
        1, 0, null, null, null, null, null, now, now);
  }

  private ServerRow row() {
    return repository.findById("mcp-it").orElseThrow();
  }

  /** The operation error, so a daemon refusal shows up in the assertion message. */
  private String failureOf() {
    return "operation_error: " + row().operationError();
  }

  private String containerId() throws Exception {
    String out = output("docker", "ps", "--all", "--quiet",
        "--filter", "label=com.docker.compose.project=" + ManagedMcpStack.PROJECT,
        "--filter", "label=com.docker.compose.service=" + serviceKey);
    return out.isBlank() ? null : out.lines().findFirst().orElse(null);
  }

  private boolean isRunning() throws Exception {
    String id = containerId();
    return id != null && "true".equals(
        output("docker", "inspect", "--format", "{{ .State.Running }}", id));
  }

  private boolean volumeExists() throws Exception {
    return !output("docker", "volume", "ls", "--quiet", "--filter", "name=^" + volumeName + "$")
        .isBlank();
  }

  private static boolean commandSucceeds(String... command) {
    try {
      return run(command) == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static int run(String... command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start();
    return process.waitFor(2, TimeUnit.MINUTES) ? process.exitValue() : -1;
  }

  private static String output(String... command) throws IOException, InterruptedException {
    Path captured = Files.createTempFile("docker-query-", ".out");
    try {
      Process process = new ProcessBuilder(command)
          .redirectOutput(captured.toFile())
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      process.waitFor(2, TimeUnit.MINUTES);
      return Files.readString(captured).trim();
    } finally {
      Files.deleteIfExists(captured);
    }
  }
}
