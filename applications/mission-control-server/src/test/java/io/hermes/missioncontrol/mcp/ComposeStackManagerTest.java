package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerHostRef;
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

  /** The daemon under test, carried in rather than resolved: see {@link ComposeStackManager}. */
  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  @TempDir
  Path stackDirectory;

  private final List<List<String>> commands = new ArrayList<>();
  private final List<Map<String, String>> environments = new ArrayList<>();

  /** Answers CLI invocations from a canned function instead of spawning docker. */
  private ComposeStackManager managerReturning(Function<List<String>, String> responder) {
    return new ComposeStackManager(stackDirectory.toString()) {
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

  // ── volume purging ──────────────────────────────────────────────────────

  @Test
  void purgingRefusesAVolumeNameOutsideTheManagedPrefix() {
    ComposeStackManager manager = managerReturning(command -> "");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.purgeVolume(HOST, "postgres_production_data"));

    assertTrue(failure.getMessage().contains("not owned by Mission Control MCP"));
    // the name check runs before anything is executed, so docker is never called
    assertTrue(commands.isEmpty());
  }

  @Test
  void purgingRefusesANullVolumeName() {
    ComposeStackManager manager = managerReturning(command -> "");

    assertThrows(IllegalArgumentException.class, () -> manager.purgeVolume(HOST, null));
  }

  @Test
  void purgingRefusesACorrectlyNamedVolumeOwnedBySomeoneElse() {
    // an operator could create a volume with our prefix by hand; the label decides
    ComposeStackManager manager = managerReturning(command -> isInspect(command) ? "someone-else" : "");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.purgeVolume(HOST, ManagedMcpStack.PROJECT + "-postgres-data"));

    assertTrue(failure.getMessage().contains("not labeled"));
    assertTrue(commands.stream().noneMatch(command -> command.contains("rm")),
        "no volume may be removed once ownership fails");
  }

  @Test
  void purgingAVolumeTheDaemonNoLongerHasIsAnsweredRatherThanThrown() {
    // absence used to be a private exception with no message that nothing outside this class
    // caught, so an operator who had removed the volume by hand got a 500 with no detail and a
    // retained row that survived every retry
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) throw new UpstreamUnavailableException(
          "Docker Compose operation failed: Error: No such volume: " + command.getLast());
      return "";
    });

    assertFalse(manager.purgeVolume(HOST, ManagedMcpStack.PROJECT + "-postgres-data"));
    assertTrue(commands.stream().noneMatch(command -> command.contains("rm")),
        "there is nothing left to remove");
  }

  @Test
  void anAbsentVolumeStillHasToClearTheNamePrefixGuardFirst() {
    // "already gone" must not become a way past the check that refuses someone else's volume
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) throw new UpstreamUnavailableException("Error: No such volume");
      return "";
    });

    assertThrows(IllegalArgumentException.class,
        () -> manager.purgeVolume(HOST, "postgres_production_data"));
    assertTrue(commands.isEmpty());
  }

  @Test
  void purgingRemovesAVolumeThatIsBothCorrectlyNamedAndOwned() {
    String volume = ManagedMcpStack.PROJECT + "-postgres-data";
    ComposeStackManager manager =
        managerReturning(command -> isInspect(command) ? ManagedMcpStack.PROJECT : "");

    manager.purgeVolume(HOST, volume);

    assertTrue(commands.stream().anyMatch(
        command -> command.contains("volume") && command.contains("rm") && command.contains(volume)));
  }

  // ── host id / path traversal ────────────────────────────────────────────

  @Test
  void aHostIdCannotEscapeTheStackDirectory() {
    ComposeStackManager manager = managerReturning(command -> "");

    // a blank or null id never reaches this method any more: DockerHostRef's own constructor
    // refuses one, which is what DockerHostRefTest pins. What is left for this guard is an id
    // that is a perfectly good string and a very bad path segment.
    for (String hostile : List.of("../../etc", "dh/../../escape", "/absolute")) {
      assertThrows(IllegalArgumentException.class,
          () -> manager.stackPath(new DockerHostRef(hostile, "unix:///sock")));
    }
  }

  @Test
  void anOrdinaryHostIdResolvesUnderTheStackDirectory() {
    ComposeStackManager manager = managerReturning(command -> "");

    Path path = manager.stackPath(HOST);

    assertTrue(path.startsWith(stackDirectory));
    assertEquals("compose.yaml", path.getFileName().toString());
  }

  // ── service lookup ──────────────────────────────────────────────────────

  @Test
  void aServiceLookupWithNoComposeFileReportsNoContainer() {
    ComposeStackManager manager = managerReturning(command -> "");

    assertNull(manager.serviceContainerId(HOST, "mcp-files"));
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
        () -> manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

    assertEquals("a network named '" + ManagedMcpStack.NETWORK
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
        () -> manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

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
      if (isInspect(command)) return ManagedMcpStack.PROJECT;
      return isDockerPs(command) ? "abc123\n" : "";
    });

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));

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

    String output = manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1));

    assertEquals("container created", output);
    assertEquals(YAML, readComposeFile());
  }

  @Test
  void aResourceReportedAsNotFoundRatherThanNoSuchIsAlsoTreatedAsMissing() {
    // 'no such volume' and 'not found' both come back from the daemon depending on the call
    ComposeStackManager manager = managerReturning(command -> {
      if (isNetworkInspect(command)) throw new UpstreamUnavailableException(
          "Docker Compose operation failed: Error: no such network: " + ManagedMcpStack.NETWORK);
      if (isVolumeInspect(command)) throw new UpstreamUnavailableException(
          "Docker Compose operation failed: get " + VOLUME + ": not found");
      return isCompose(command) ? "ok" : "";
    });

    assertEquals("ok", manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
  }

  @Test
  void anOwnedNetworkVolumeAndContainerAreAdopted() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ManagedMcpStack.PROJECT;
      if (isDockerPs(command)) return "abc123\ndef456\n";
      return isCompose(command) ? "ok" : "";
    });

    assertEquals("ok", manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
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
        () -> manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
    assertTrue(commands.stream().noneMatch(ComposeStackManagerTest::isCompose));
  }

  @Test
  void ownershipLabelsAreReadFromConfigLabelsForContainersAndTopLevelForTheRest() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ManagedMcpStack.PROJECT;
      return isDockerPs(command) ? "abc123\n" : "";
    });

    manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1));

    assertTrue(formatOf(commands.stream().filter(ComposeStackManagerTest::isNetworkInspect).findFirst()
        .orElseThrow()).contains("index .Labels"));
    assertTrue(formatOf(commands.stream().filter(ComposeStackManagerTest::isContainerInspect).findFirst()
        .orElseThrow()).contains("index .Config.Labels"));
  }

  @Test
  void executeWritesTheFileAndHandsComposeTheProjectTheFileAndTheRenderedEnvironment() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ManagedMcpStack.PROJECT;
      return "";
    });

    manager.execute(HOST, rendered(), List.of("up", "-d", "--wait"), Duration.ofMinutes(1));

    List<String> compose = commands.stream().filter(ComposeStackManagerTest::isCompose).findFirst().orElseThrow();
    assertEquals(List.of("docker", "--host", "unix:///sock", "compose",
        "--project-name", ManagedMcpStack.PROJECT,
        "--file", composeFile().toString(), "up", "-d", "--wait"), compose);
    // the decrypted secrets travel in the process environment, never in the file
    assertEquals(Map.of("MC_MCP_0011", "secret"), environments.get(commands.indexOf(compose)));
    assertEquals(YAML, readComposeFile());
  }

  // ── writing the stack file ──────────────────────────────────────────────

  @Test
  void writeOnlyWritesTheFileAndTouchesNoDaemon() {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly(HOST, rendered());

    assertEquals(YAML, readComposeFile());
    assertTrue(commands.isEmpty());
  }

  @Test
  void rewritingReplacesTheFileAndLeavesNoTemporaryBehind() throws Exception {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly(HOST, rendered());
    manager.writeOnly(HOST, new ComposeStackRenderer.Rendered(
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
    ComposeStackManager manager = new ComposeStackManager(blocked.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.writeOnly(HOST, rendered()));

    assertTrue(failure.getMessage().startsWith("could not write managed MCP Compose file:"),
        failure.getMessage());
  }

  // ── service lookup ──────────────────────────────────────────────────────

  @Test
  void aServiceLookupReturnsTheFirstIdOnceAStackExists() {
    ComposeStackManager manager = managerReturning(command -> "abc123\ndef456\n");
    manager.writeOnly(HOST, rendered());

    assertEquals("abc123", manager.serviceContainerId(HOST, SERVICE));
    assertEquals(List.of("docker", "--host", "unix:///sock", "compose",
        "--project-name", ManagedMcpStack.PROJECT,
        "--file", composeFile().toString(), "ps", "--all", "-q", SERVICE),
        commands.getFirst());
  }

  @Test
  void aServiceLookupTreatsBlankOutputAsNoContainer() {
    ComposeStackManager manager = managerReturning(command -> "  \n");
    manager.writeOnly(HOST, rendered());

    assertNull(manager.serviceContainerId(HOST, SERVICE));
  }

  // ── reclaiming departed services ────────────────────────────────────────

  @Test
  void removingADepartedServiceRefusesAContainerThatIsNotOurs() {
    ComposeStackManager manager =
        managerReturning(command -> isInspect(command) ? "someone-else" : "cid-1\n");

    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> manager.removeServices(HOST, "mcp-files", List.of("mcp-files-database"),
            Duration.ofMinutes(2)));

    assertTrue(failure.getMessage().contains("not owned by Mission Control MCP"));
    assertTrue(commands.stream().noneMatch(command -> command.contains("rm")),
        "a container that is not ours is never removed");
  }

  @Test
  void removingADepartedServiceStopsAndRemovesTheContainerItFoundByLabel() {
    ComposeStackManager manager =
        managerReturning(command -> isInspect(command) ? ManagedMcpStack.PROJECT : "cid-1\n");

    manager.removeServices(HOST, "mcp-files", List.of("mcp-files-database"),
        Duration.ofMinutes(2));

    List<String> lookup = commands.getFirst();
    assertTrue(lookup.contains("label=" + ManagedMcpStack.SERVER_ID_LABEL + "=mcp-files"));
    assertTrue(lookup.contains("label=com.docker.compose.service=mcp-files-database"));
    // --force stops it first; --volumes drops only what the image declared anonymously, never
    // the named volumes the caller is about to retain
    assertEquals(List.of("docker", "--host", "unix:///sock", "rm", "--force", "--volumes", "cid-1"),
        commands.getLast());
  }

  @Test
  void removingNoDepartedServicesTouchesTheDaemonNotAtAll() {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.removeServices(HOST, "mcp-files", List.of(), Duration.ofMinutes(2));

    assertTrue(commands.isEmpty());
  }

  @Test
  void whatARecordStillHasIsReadBackFromItsOwnLabelWithBlankLinesDropped() {
    ComposeStackManager manager = managerReturning(command -> "mcp-files\n\n mcp-files-database \n");

    assertEquals(List.of("mcp-files", "mcp-files-database"),
        manager.servicesOf(HOST, "mcp-files"));
    assertTrue(commands.getFirst().contains("label=" + ManagedMcpStack.SERVER_ID_LABEL + "=mcp-files"));

    assertEquals(List.of("mcp-files", "mcp-files-database"),
        manager.volumesOf(HOST, "mcp-files"));
    assertTrue(commands.getLast().contains("volume"));
  }

  @Test
  void oneReadMapsEveryManagedContainerToItsComposeService() {
    // what the catalog listing uses instead of forking `docker compose ps` per row under the
    // host lock. Keyed by service, not by server id, because a record's support services carry
    // the same server-id label and their state is not the record's.
    ComposeStackManager manager = managerReturning(command ->
        "mcp-files\tcid-files\nmcp-files-database\tcid-database\nmcp-docs\tcid-docs\n");

    Map<String, String> byService = manager.containerIdsByService(HOST);

    assertEquals(Map.of(
        "mcp-files", "cid-files",
        "mcp-files-database", "cid-database",
        "mcp-docs", "cid-docs"), byService);
    assertTrue(commands.getFirst().contains(
        "label=com.docker.compose.project=" + ManagedMcpStack.PROJECT));
    // full ids: the caller joins these against the Engine API's 64-character ids, and the
    // CLI's default 12-character truncation matches nothing there
    assertTrue(commands.getFirst().contains("--no-trunc"), commands.getFirst().toString());
    assertEquals(1, commands.size(), "one read for the whole host: " + commands);
  }

  @Test
  void aCatalogReadDoesNotWaitOutAMutationHoldingTheHostLock() throws Exception {
    // the batch listing exists so the page stops waiting on an image pull; a read that takes
    // the mutation lock re-creates exactly that wait, up to the compose timeout
    java.util.concurrent.CountDownLatch mutationRunning = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) return ManagedMcpStack.PROJECT;
      if (command.contains("up")) {   // the mutation, holding the host lock mid-"pull"
        mutationRunning.countDown();
        try {
          release.await(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
        return "ok";
      }
      if (isCompose(command)) return "cid-files\n";   // compose ps -q for one service
      return "mcp-files\tcid-files\n";                // the label-filtered docker ps
    });
    Thread mutation = new Thread(() ->
        manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1)));
    mutation.start();
    try {
      assertTrue(mutationRunning.await(5, java.util.concurrent.TimeUnit.SECONDS));

      assertEquals(Map.of("mcp-files", "cid-files"), manager.containerIdsByService(HOST));
      assertEquals("cid-files", manager.serviceContainerId(HOST, "mcp-files"));
    } finally {
      release.countDown();
      mutation.join(5_000);
    }
  }

  @Test
  void aContainerTheDaemonNamesNoServiceForIsSkippedRatherThanKeyedOnBlank() {
    // `--format` prints an empty column for a label that is not set, and a blank key would
    // collide with every other unlabelled container
    ComposeStackManager manager = managerReturning(command ->
        "\tcid-orphan\nmcp-files\tcid-files\nno-tab-at-all\n");

    assertEquals(Map.of("mcp-files", "cid-files"), manager.containerIdsByService(HOST));
  }

  @Test
  void aContainerThatVanishesBetweenBeingListedAndBeingInspectedIsSkipped() {
    // both of these list container ids and then inspect each one for its owner label. A
    // container that goes away in between used to raise the private missing-resource type,
    // which neither caller caught — a cleanup would report a failure with no message for a
    // container that was already gone
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) throw new UpstreamUnavailableException("Error response from daemon: not found");
      return command.contains("ps") ? "cid-vanished\n" : "";
    });

    manager.removeServices(HOST, "mcp-files", List.of("mcp-files-database"),
        Duration.ofMinutes(1));

    assertTrue(commands.stream().noneMatch(command -> command.contains("rm")),
        "there is nothing left to remove");
  }

  @Test
  void aComposeServiceWhoseContainerVanishedMidCheckDoesNotBlockTheStack() {
    ComposeStackManager manager = managerReturning(command -> {
      if (isInspect(command)) throw new UpstreamUnavailableException("Error response from daemon: not found");
      return command.contains("ps") ? "cid-vanished\n" : "";
    });

    // the ownership guard has nothing to refuse, so the run goes ahead
    manager.execute(HOST, rendered(), List.of("up", "--no-start"), Duration.ofMinutes(1));

    assertTrue(commands.getLast().contains("up"));
  }

  // ── the CLI runner itself ───────────────────────────────────────────────
  //
  // Everything above substitutes run(). These exercise the real one with /bin/sh instead of
  // docker, because its exit-code, timeout and start-failure handling is what turns a broken
  // daemon into a 503 rather than an opaque 500.

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void theRunnerCapturesOutputAndPassesTheEnvironmentToTheProcess() {
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

    String output = manager.run(List.of("/bin/sh", "-c", "printf %s \"$MC_TEST_SECRET\""),
        Map.of("MC_TEST_SECRET", "from-environment"), Duration.ofSeconds(10));

    assertEquals("from-environment", output);
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aNonZeroExitBecomesAnUpstreamFailureCarryingTheOutput() {
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "printf 'service unhealthy'; exit 3"),
            Map.of(), Duration.ofSeconds(10)));

    assertEquals("Docker Compose operation failed: service unhealthy", failure.getMessage());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aSilentNonZeroExitStillProducesAUsableMessage() {
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "exit 1"), Map.of(), Duration.ofSeconds(10)));

    assertEquals("Docker Compose operation failed", failure.getMessage());
  }

  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  void aCommandThatOverrunsItsTimeoutIsKilled() {
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

    UpstreamUnavailableException failure = assertThrows(UpstreamUnavailableException.class,
        () -> manager.run(List.of("/bin/sh", "-c", "sleep 30"), Map.of(), Duration.ofMillis(200)));

    assertEquals("Docker Compose operation timed out", failure.getMessage());
  }

  @Test
  void aCommandThatCannotBeStartedIsReportedAsAnUpstreamFailure() {
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

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
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString());

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
    ComposeStackManager manager = new ComposeStackManager(stackDirectory.toString()) {
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
        return isInspect(command) ? ManagedMcpStack.PROJECT : "";
      }
    };

    List<Thread> writers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      writers.add(Thread.ofPlatform().start(() ->
          manager.execute(HOST, rendered(), List.of("up", "-d"), Duration.ofMinutes(1))));
    }
    for (Thread writer : writers) writer.join(Duration.ofSeconds(30));

    assertEquals(0, overlaps.get(), "two Compose runs for one daemon overlapped");
    assertEquals(YAML, readComposeFile());
  }

  @Test
  void aDifferentHostGetsItsOwnLockAndItsOwnStackFile() {
    ComposeStackManager manager = managerReturning(command -> "");

    manager.writeOnly(HOST, rendered());
    manager.writeOnly(new DockerHostRef("dh-remote", "unix:///sock"), new ComposeStackRenderer.Rendered(
        "services: {remote}\n", Map.of(), Map.of(), Map.of()));

    assertEquals(YAML, readComposeFile());
    assertTrue(Files.exists(stackDirectory.resolve("dh-remote").resolve("compose.yaml")));
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static final String SERVICE = "mcp-files";
  private static final String VOLUME = ManagedMcpStack.PROJECT + "-files-data";
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
