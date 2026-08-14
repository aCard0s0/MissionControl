package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.hosts.HostService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

  /** Answers CLI invocations from a canned function instead of spawning docker. */
  private ComposeStackManager managerReturning(Function<List<String>, String> responder) {
    return new ComposeStackManager(hosts, stackDirectory.toString()) {
      @Override
      String run(List<String> command, Map<String, String> environment, Duration timeout) {
        commands.add(command);
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
}
