package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guards that stand between Mission Control and someone else's Docker resources.
 *
 * <p>Every one of them refuses to destroy or adopt a container, network or volume that is
 * not labelled {@code io.hermes.mission-control.owner=mission-control-mcp}. They are the
 * reason a name collision with an unrelated stack is an error rather than data loss, and
 * until the CLI call became substitutable none of them ran outside production.
 */
class ComposeStackManagerTest {

  @TempDir
  Path stackDirectory;

  private HostService hosts;
  private final List<List<String>> commands = new ArrayList<>();
  private final List<Map<String, String>> environments = new ArrayList<>();

  /** Answers CLI invocations from a canned function instead of spawning docker. */
  private ComposeStackManager managerReturning(Function<List<String>, String> responder) {
    return new ComposeStackManager(hosts, stackDirectory.toString()) {
      @Override
      String run(List<String> command, Map<String, String> environment, Duration timeout) {
        commands.add(command);
        environments.add(environment);
        return responder.apply(command);
      }
    };
  }

  private static boolean isInspect(List<String> command) {
    return command.contains("inspect");
  }

  @BeforeEach
  void setUp() {
    hosts = mock(HostService.class);
    when(hosts.urlOf(anyString())).thenReturn("unix:///sock");
  }

  // ── volume purging ──────────────────────────────────────────────────────

  @Test
  void purgingRefusesAVolumeNameOutsideTheManagedPrefix() {
    ComposeStackManager manager = managerReturning(command -> "");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.purgeVolume("dh-local", "postgres_production_data"));

    assertTrue(failure.getMessage().contains("not owned by Mission Control MCP"));
    // the name check runs before anything is executed, so docker is never called
    assertTrue(commands.isEmpty());
  }

  @Test
  void purgingRefusesANullVolumeName() {
    ComposeStackManager manager = managerReturning(command -> "");

    assertThrows(IllegalArgumentException.class, () -> manager.purgeVolume("dh-local", null));
  }

  @Test
  void purgingRefusesACorrectlyNamedVolumeOwnedBySomeoneElse() {
    // an operator could create a volume with our prefix by hand; the label decides
    ComposeStackManager manager = managerReturning(command -> isInspect(command) ? "someone-else" : "");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.purgeVolume("dh-local", ComposeStackRenderer.PROJECT + "-postgres-data"));

    assertTrue(failure.getMessage().contains("not labeled"));
    assertTrue(commands.stream().noneMatch(command -> command.contains("rm")),
        "no volume may be removed once ownership fails");
  }

  @Test
  void purgingRemovesAVolumeThatIsBothCorrectlyNamedAndOwned() {
    String volume = ComposeStackRenderer.PROJECT + "-postgres-data";
    ComposeStackManager manager =
        managerReturning(command -> isInspect(command) ? ComposeStackRenderer.PROJECT : "");

    manager.purgeVolume("dh-local", volume);

    assertTrue(commands.stream().anyMatch(
        command -> command.contains("volume") && command.contains("rm") && command.contains(volume)));
  }

  // ── host id / path traversal ────────────────────────────────────────────

  @Test
  void aHostIdCannotEscapeTheStackDirectory() {
    ComposeStackManager manager = managerReturning(command -> "");

    assertThrows(IllegalArgumentException.class, () -> manager.stackPath("../../etc"));
    assertThrows(IllegalArgumentException.class, () -> manager.stackPath("dh/../../escape"));
    assertThrows(IllegalArgumentException.class, () -> manager.stackPath("/absolute"));
    assertThrows(IllegalArgumentException.class, () -> manager.stackPath(""));
    assertThrows(IllegalArgumentException.class, () -> manager.stackPath(null));
  }

  @Test
  void anOrdinaryHostIdResolvesUnderTheStackDirectory() {
    ComposeStackManager manager = managerReturning(command -> "");

    Path path = manager.stackPath("dh-local");

    assertTrue(path.startsWith(stackDirectory));
    assertEquals("compose.yaml", path.getFileName().toString());
  }

  // ── service lookup ──────────────────────────────────────────────────────

  @Test
  void aServiceLookupWithNoComposeFileReportsNoContainer() {
    ComposeStackManager manager = managerReturning(command -> "");

    assertNull(manager.serviceContainerId("dh-local", "mcp-files"));
    // nothing to ask docker about until a stack has been written
    assertTrue(commands.isEmpty());
  }

  // ── stack execution: the ownership gate ─────────────────────────────────
  //
  // execute() inspects every network, volume and Compose service the render touches before
  // it writes the file or runs Compose. A name it does not own is an error, and nothing may
  // be written or run after one is found.

  @Test
  void aForeignNetworkStopsTheStackBeforeAnythingIsWritten() {
    ComposeStackManager manager = managerReturning(command ->
        isNetworkInspect(command) ? "someone-else" : "");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

    assertEquals("a network named '" + ComposeStackRenderer.NETWORK
        + "' already exists but is not owned by Mission Control MCP", failure.getMessage());
    assertTrue(commands.stream().noneMatch(ComposeStackManagerTest::isCompose),
        "Compose must not run once ownership fails");
    assertFalse(Files.exists(composeFile()), "no Compose file may be written once ownership fails");
  }

  @Test
  void aForeignVolumeStopsTheStack() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isNetworkInspect(command)) throw missing("network");
      return isVolumeInspect(command) ? "someone-else" : "";
    });

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

    assertEquals("a volume named '" + VOLUME + "' already exists but is not owned by Mission Control MCP",
        failure.getMessage());
    assertTrue(commands.stream().noneMatch(ComposeStackManagerTest::isCompose));
  }

  @Test
  void aForeignComposeServiceContainerStopsTheStack() {
    // the project/service labels can match a container an operator started by hand with the
    // same project name; our own owner label is the tiebreak
    ComposeStackManager manager = managerReturning(command -> {
      if (isContainerInspect(command)) return "someone-else";
      if (isInspect(command)) return ComposeStackRenderer.PROJECT;
      return isDockerPs(command) ? "abc123\n" : "";
    });

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

    assertEquals("a Compose container for service '" + SERVICE
        + "' exists but is not owned by Mission Control MCP", failure.getMessage());
    assertTrue(commands.stream().noneMatch(ComposeStackManagerTest::isCompose));
  }

  @Test
  void aMissingResourceIsFineBecauseComposeCreatesIt() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) throw missing(command.contains("network") ? "network" : "volume");
      return isCompose(command) ? "container created" : "";
    });

    String output = manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1));

    assertEquals("container created", output);
    assertEquals(YAML, readComposeFile());
  }

  @Test
  void aResourceReportedAsNotFoundRatherThanNoSuchIsAlsoTreatedAsMissing() {
    // 'no such volume' and 'not found' both come back from the daemon depending on the call
    ComposeStackManager manager = managerReturning(command -> {
      if (isNetworkInspect(command)) throw new UpstreamUnavailableException(
          "Docker Compose operation failed: Error: no such network: " + ComposeStackRenderer.NETWORK);
      if (isVolumeInspect(command)) throw new UpstreamUnavailableException(
          "Docker Compose operation failed: get " + VOLUME + ": not found");
      return isCompose(command) ? "ok" : "";
    });

    assertEquals("ok", manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
  }

  @Test
  void anOwnedNetworkVolumeAndContainerAreAdopted() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ComposeStackRenderer.PROJECT;
      if (isDockerPs(command)) return "abc123\ndef456\n";
      return isCompose(command) ? "ok" : "";
    });

    assertEquals("ok", manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
    // both listed containers were checked, not just the first
    assertEquals(2, commands.stream().filter(ComposeStackManagerTest::isContainerInspect).count());
  }

  @Test
  void anInspectFailureThatIsNotAMissingResourceIsNotSwallowed() {
    // a dead daemon must not be mistaken for 'the network does not exist yet', which would
    // let the run continue and report a confusing failure further down
    ComposeStackManager manager = managerReturning(command -> {
      if (isNetworkInspect(command)) {
        throw new UpstreamUnavailableException("could not start Docker CLI: daemon socket is gone");
      }
      return "";
    });

    assertThrows(UpstreamUnavailableException.class,
        () -> manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
    assertTrue(commands.stream().noneMatch(ComposeStackManagerTest::isCompose));
  }

  @Test
  void ownershipLabelsAreReadFromConfigLabelsForContainersAndTopLevelForTheRest() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ComposeStackRenderer.PROJECT;
      return isDockerPs(command) ? "abc123\n" : "";
    });

    manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1));

    assertTrue(formatOf(commands.stream().filter(ComposeStackManagerTest::isNetworkInspect).findFirst()
        .orElseThrow()).contains("index .Labels"));
    assertTrue(formatOf(commands.stream().filter(ComposeStackManagerTest::isContainerInspect).findFirst()
        .orElseThrow()).contains("index .Config.Labels"));
  }

  @Test
  void executeWritesTheFileAndHandsComposeTheProjectTheFileAndTheRenderedEnvironment() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ComposeStackRenderer.PROJECT;
      return "";
    });

    manager.execute("dh-local", rendered(), List.of("up", "-d", "--wait"), Duration.ofMinutes(1));

    List<String> compose = commands.stream().filter(ComposeStackManagerTest::isCompose).findFirst().orElseThrow();
    assertEquals(List.of("docker", "--host", "unix:///sock", "compose",
        "--project-name", ComposeStackRenderer.PROJECT,
        "--file", composeFile().toString(), "up", "-d", "--wait"), compose);
    // the decrypted secrets travel in the process environment, never in the file
    assertEquals(Map.of("MC_MCP_0011", "secret"), environments.get(commands.indexOf(compose)));
    assertEquals(YAML, readComposeFile());
  }

  // ── writing the stack file ──────────────────────────────────────────────

  @Test
  void writeOnlyWritesTheFileAndTouchesNoDaemon() {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly("dh-local", rendered());

    assertEquals(YAML, readComposeFile());
    assertTrue(commands.isEmpty());
  }

  @Test
  void rewritingReplacesTheFileAndLeavesNoTemporaryBehind() throws Exception {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly("dh-local", rendered());
    manager.writeOnly("dh-local", new ComposeStackRenderer.Rendered(
        "services: {second}\n", Map.of(), Map.of(), Map.of()));

    assertEquals("services: {second}\n", readComposeFile());
    try (var entries = Files.list(composeFile().getParent())) {
      assertEquals(List.of("compose.yaml"), entries.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void aStackDirectoryThatCannotBeCreatedIsReportedAsAnUpstreamFailure() throws Exception {
    // 503, not 500: the request was fine, the host's disk is not
    Path blocked = Files.createFile(stackDirectory.resolve("blocked"));
    ComposeStackManager manager = new ComposeStackManager(hosts, blocked.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.writeOnly("dh-local", rendered()));

    assertTrue(failure.getMessage().startsWith("could not write managed MCP Compose file:"),
        failure.getMessage());
  }

  // ── service lookup ──────────────────────────────────────────────────────

  @Test
  void aServiceLookupReturnsTheFirstIdOnceAStackExists() {
    ComposeStackManager manager = managerReturning(command -> "abc123\ndef456\n");
    manager.writeOnly("dh-local", rendered());

    assertEquals("abc123", manager.serviceContainerId("dh-local", SERVICE));
    assertEquals(List.of("docker", "--host", "unix:///sock", "compose",
        "--project-name", ComposeStackRenderer.PROJECT,
        "--file", composeFile().toString(), "ps", "--all", "-q", SERVICE),
        commands.getFirst());
  }

  @Test
  void aServiceLookupTreatsBlankOutputAsNoContainer() {
    ComposeStackManager manager = managerReturning(command -> "  \n");
    manager.writeOnly("dh-local", rendered());

    assertNull(manager.serviceContainerId("dh-local", SERVICE));
  }

  // ── the CLI runner itself ───────────────────────────────────────────────
  //
  // Everything above substitutes run(). These exercise the real one with /bin/sh instead of
  // docker, because its exit-code, timeout and start-failure handling is what turns a broken
  // daemon into a 503 rather than an opaque 500.

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void theRunnerCapturesOutputAndPassesTheEnvironmentToTheProcess() {
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    String output = manager.run(List.of("/bin/sh", "-c", "printf %s \"$MC_TEST_SECRET\""),
        Map.of("MC_TEST_SECRET", "from-environment"), Duration.ofSeconds(10));

    assertEquals("from-environment", output);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aNonZeroExitBecomesAnUpstreamFailureCarryingTheOutput() {
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "printf 'service unhealthy'; exit 3"),
            Map.of(), Duration.ofSeconds(10)));

    assertEquals("Docker Compose operation failed: service unhealthy", failure.getMessage());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aSilentNonZeroExitStillProducesAUsableMessage() {
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "exit 1"), Map.of(), Duration.ofSeconds(10)));

    assertEquals("Docker Compose operation failed", failure.getMessage());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aCommandThatOverrunsItsTimeoutIsKilled() {
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "sleep 30"), Map.of(), Duration.ofMillis(200)));

    assertEquals("Docker Compose operation timed out", failure.getMessage());
  }

  @Test
  void aCommandThatCannotBeStartedIsReportedAsAnUpstreamFailure() {
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of(stackDirectory.resolve("no-such-binary").toString()),
            Map.of(), Duration.ofSeconds(10)));

    assertTrue(failure.getMessage().startsWith("could not start Docker CLI:"), failure.getMessage());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aFloodOfOutputIsCappedSoOneBadRunCannotExhaustTheHeap() {
    // Compose can emit megabytes on a failing pull; the whole string ends up in an exception
    // message and then in an HTTP error body
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString());

    String output = manager.run(
        List.of("/bin/sh", "-c", "head -c 200000 /dev/zero | tr '\\0' x"),
        Map.of(), Duration.ofSeconds(20));

    assertEquals(32_768, output.length());
  }

  @Test
  void mutationsOnOneDaemonAreSerialisedSoTwoStacksCannotInterleave() throws Exception {
    // both writes target the same compose.yaml and the same Docker resources; overlapping them
    // is how a half-written file gets handed to Compose
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger overlaps = new AtomicInteger();
    ComposeStackManager manager = new ComposeStackManager(hosts, stackDirectory.toString()) {
      @Override
      String run(List<String> command, Map<String, String> environment, Duration timeout) {
        if (inFlight.incrementAndGet() > 1) overlaps.incrementAndGet();
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          inFlight.decrementAndGet();
        }
        return isInspect(command) ? ComposeStackRenderer.PROJECT : "";
      }
    };

    List<Thread> writers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      writers.add(Thread.ofPlatform().start(() ->
          manager.execute("dh-local", rendered(), List.of("up", "-d"), Duration.ofMinutes(1))));
    }
    for (Thread writer : writers) writer.join(Duration.ofSeconds(30));

    assertEquals(0, overlaps.get(), "two Compose runs for one daemon overlapped");
    assertEquals(YAML, readComposeFile());
  }

  @Test
  void aDifferentHostGetsItsOwnLockAndItsOwnStackFile() {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly("dh-local", rendered());
    manager.writeOnly("dh-remote", new ComposeStackRenderer.Rendered(
        "services: {remote}\n", Map.of(), Map.of(), Map.of()));

    assertEquals(YAML, readComposeFile());
    assertTrue(Files.exists(stackDirectory.resolve("dh-remote").resolve("compose.yaml")));
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static final String SERVICE = "mcp-files";
  private static final String VOLUME = ComposeStackRenderer.PROJECT + "-files-data";
  private static final String YAML = "services:\n  mcp-files: {}\n";

  private static ComposeStackRenderer.Rendered rendered() {
    return new ComposeStackRenderer.Rendered(
        YAML,
        Map.of("MC_MCP_0011", "secret"),
        Map.of("srv-files", List.of(SERVICE)),
        Map.of("srv-files", List.of(VOLUME)));
  }

  private Path composeFile() {
    return stackDirectory.resolve("dh-local").resolve("compose.yaml");
  }

  private String readComposeFile() {
    try {
      return Files.readString(composeFile());
    } catch (IOException e) {
      throw new AssertionError("no Compose file was written", e);
    }
  }

  /** What the daemon says when the resource simply is not there yet. */
  private static UpstreamUnavailableException missing(String type) {
    return new UpstreamUnavailableException(
        "Docker Compose operation failed: Error: No such " + type + ": whatever-it-was");
  }

  private static String formatOf(List<String> command) {
    return command.get(command.indexOf("--format") + 1);
  }

  private static boolean isCompose(List<String> command) {
    return command.contains("compose");
  }

  private static boolean isDockerPs(List<String> command) {
    return command.contains("ps") && !isCompose(command);
  }

  private static boolean isNetworkInspect(List<String> command) {
    return isInspect(command) && command.contains("network");
  }

  private static boolean isVolumeInspect(List<String> command) {
    return isInspect(command) && command.contains("volume");
  }

  private static boolean isContainerInspect(List<String> command) {
    return isInspect(command) && command.contains("container");
  }
}
