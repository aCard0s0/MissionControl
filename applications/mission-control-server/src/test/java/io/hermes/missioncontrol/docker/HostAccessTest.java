package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Laying an operator's host access over a container's environment — the half of an update that
 * a deploy never exercises, since a new container has no environment to lay anything over.
 */
class HostAccessTest {

  private static final List<String> IMAGE_ENV =
      List.of("PATH=/usr/bin", "HERMES_WRITE_SAFE_ROOT=/opt/data", "TZ=UTC");

  @Test
  void aVariableReplacesTheLineCarryingItsKeyAndNewOnesAreAppended() {
    HostAccess access = new HostAccess(List.of(),
        List.of(new HostAccess.EnvVar("TZ", "Europe/Lisbon"), new HostAccess.EnvVar("HERMES_DASHBOARD", "1")),
        List.of());

    assertEquals(List.of("PATH=/usr/bin", "HERMES_WRITE_SAFE_ROOT=/opt/data", "TZ=Europe/Lisbon", "HERMES_DASHBOARD=1"),
        access.environment(IMAGE_ENV));
  }

  @Test
  void aWritableMountWidensTheRootTheContainerAlreadyHasWithoutRepeatingIt() {
    HostAccess access = new HostAccess(List.of(), List.of(),
        List.of(new HostAccess.Mount("/srv/repo", "/work", false), new HostAccess.Mount("/srv/docs", "/docs", true)));

    // widened once on the deploy, widened again here: /work stays a single entry
    assertEquals(List.of("HERMES_WRITE_SAFE_ROOT=/opt/data:/work"),
        access.environment(List.of("HERMES_WRITE_SAFE_ROOT=/opt/data:/work")));
    assertEquals(List.of("PATH=/usr/bin", "HERMES_WRITE_SAFE_ROOT=/opt/data:/work", "TZ=UTC"),
        access.environment(IMAGE_ENV));
  }

  @Test
  void aBlankSegmentInTheRootIsDroppedRatherThanKeptAsAnEmptyEntry() {
    HostAccess access = new HostAccess(List.of(), List.of(),
        List.of(new HostAccess.Mount("/srv/repo", "/work", false)));

    assertEquals(List.of("HERMES_WRITE_SAFE_ROOT=/opt/data:/work"),
        access.environment(List.of("HERMES_WRITE_SAFE_ROOT=/opt/data:")));
  }

  @Test
  void emptyMeansNothingOfAnyKindWasAsked() {
    assertTrue(HostAccess.NONE.isEmpty());
    assertFalse(new HostAccess(List.of(new HostAccess.PortMapping(9119, 9119, "")), List.of(), List.of()).isEmpty());
    assertFalse(new HostAccess(List.of(), List.of(new HostAccess.EnvVar("A", "1")), List.of()).isEmpty());
    assertFalse(new HostAccess(List.of(), List.of(),
        List.of(new HostAccess.Mount("/srv", "/docs", true))).isEmpty());
  }

  @Test
  void aBlankOrAbsentBindAddressIsLoopbackAndMountPathsMustBothBeAbsolute() {
    assertEquals("127.0.0.1", new HostAccess.PortMapping(9119, 9119, null).bindIp());
    assertEquals("127.0.0.1", new HostAccess.PortMapping(9119, 9119, " ").bindIp());
    assertEquals("0.0.0.0", new HostAccess.PortMapping(9119, 9119, "0.0.0.0").bindIp());
    assertThrows(IllegalArgumentException.class, () -> new HostAccess(List.of(), List.of(),
        List.of(new HostAccess.Mount("srv/repo", "/work", false))));
    assertThrows(IllegalArgumentException.class, () -> new HostAccess(List.of(), List.of(),
        List.of(new HostAccess.Mount("/srv/repo", "work", false))));
  }

  @Test
  void anOperatorsOwnRootWinsAndNothingIsAddedWhenNothingWasAsked() {
    HostAccess own = new HostAccess(List.of(),
        List.of(new HostAccess.EnvVar("HERMES_WRITE_SAFE_ROOT", "/work/one")),
        List.of(new HostAccess.Mount("/srv/a", "/work/one", false), new HostAccess.Mount("/srv/b", "/work/two", false)));

    assertEquals(List.of("PATH=/usr/bin", "HERMES_WRITE_SAFE_ROOT=/work/one", "TZ=UTC"), own.environment(IMAGE_ENV));
    assertEquals(IMAGE_ENV, HostAccess.NONE.environment(IMAGE_ENV));
    // a line with no `=` is kept as a name with a blank value rather than dropped
    assertEquals(List.of("DEBUG="), HostAccess.NONE.environment(List.of("DEBUG")));
  }
}
