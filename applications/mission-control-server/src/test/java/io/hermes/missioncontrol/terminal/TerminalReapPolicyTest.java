package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Each terminal holds a live {@code docker exec} stream and JVM-side reader/writer threads,
 * so a session that should be reaped and isn't leaks threads until the process dies. These
 * are the bounds that prevent that.
 */
class TerminalReapPolicyTest {

  private static final long NOW = 1_700_000_000_000L;

  private static final TerminalProperties PROPS = new TerminalProperties(
      50, 5,
      Duration.ofMinutes(30),   // idle
      Duration.ofHours(8),      // max lifetime
      Duration.ofSeconds(30),   // heartbeat
      Duration.ofSeconds(90),   // pong
      "hermes");

  /** A session created, active and ponging right now. */
  private static String reasonFor(long ageMs, long idleMs, long sincePongMs) {
    return TerminalSocketHandler.reapReason(
        NOW, NOW - ageMs, NOW - idleMs, NOW - sincePongMs, PROPS);
  }

  @Test
  void aHealthySessionIsKept() {
    assertNull(reasonFor(Duration.ofMinutes(5).toMillis(), 1_000, 1_000));
  }

  @Test
  void aSessionPastTheLifetimeCeilingIsReaped() {
    assertEquals("max-lifetime", reasonFor(Duration.ofHours(9).toMillis(), 0, 0));
  }

  @Test
  void anIdleSessionIsReaped() {
    assertEquals("idle", reasonFor(Duration.ofHours(1).toMillis(), Duration.ofMinutes(31).toMillis(), 0));
  }

  @Test
  void aSessionThatStoppedPongingIsReaped() {
    // the half-open-TCP case: the browser is gone but the socket still looks open
    assertEquals("pong-timeout",
        reasonFor(Duration.ofMinutes(5).toMillis(), 0, Duration.ofSeconds(91).toMillis()));
  }

  @Test
  void theLifetimeCeilingOutranksIdlenessAndAMissingPong() {
    // all three are true at once; the reported reason must be the real cause
    assertEquals("max-lifetime", reasonFor(
        Duration.ofHours(9).toMillis(),
        Duration.ofMinutes(31).toMillis(),
        Duration.ofSeconds(91).toMillis()));
  }

  @Test
  void idlenessOutranksAMissingPong() {
    assertEquals("idle", reasonFor(
        Duration.ofHours(1).toMillis(),
        Duration.ofMinutes(31).toMillis(),
        Duration.ofSeconds(91).toMillis()));
  }

  @Test
  void aSessionExactlyAtABoundIsKept() {
    // the comparison is strictly greater-than; reaping at the boundary would cut a
    // session short by one tick and reaping one millisecond later would not
    assertNull(reasonFor(Duration.ofHours(8).toMillis(), 0, 0));
    assertNull(reasonFor(0, Duration.ofMinutes(30).toMillis(), 0));
    assertNull(reasonFor(0, 0, Duration.ofSeconds(90).toMillis()));

    assertEquals("max-lifetime", reasonFor(Duration.ofHours(8).toMillis() + 1, 0, 0));
    assertEquals("idle", reasonFor(0, Duration.ofMinutes(30).toMillis() + 1, 0));
    assertEquals("pong-timeout", reasonFor(0, 0, Duration.ofSeconds(90).toMillis() + 1));
  }

  @Test
  void eachTimestampDrivesItsOwnRule() {
    // four adjacent long parameters — a swapped argument at the call site still compiles,
    // so each rule is pinned to the one timestamp it is supposed to read
    assertNull(reasonFor(Duration.ofHours(7).toMillis(), 0, 0));
    assertNull(reasonFor(0, Duration.ofMinutes(29).toMillis(), 0));
    assertNull(reasonFor(0, 0, Duration.ofSeconds(89).toMillis()));
  }

  @Test
  void anUnsetUserDefaultsToHermesRatherThanTheImageDefault() {
    // the default matters more than the other defaults do: it is the difference between a web
    // shell that writes /opt/data as the agent and one that writes it as root
    assertEquals("hermes", new TerminalProperties(null, null, null, null, null, null, null).user());
    // and an operator who blanks it still gets the image default, not the fallback back
    assertEquals("", new TerminalProperties(null, null, null, null, null, null, "").user());
  }

  @Test
  void defaultedPropertiesStillProduceUsableBounds() {
    TerminalProperties defaults = new TerminalProperties(null, null, null, null, null, null, null);

    assertNull(TerminalSocketHandler.reapReason(NOW, NOW, NOW, NOW, defaults));
    assertEquals("max-lifetime", TerminalSocketHandler.reapReason(
        NOW, NOW - Duration.ofHours(9).toMillis(), NOW, NOW, defaults));
  }
}
