package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

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

  // ── where the values come from ──────────────────────────────────────────

  /**
   * application.yml carries no numbers for these — the record's compact constructor is the one
   * place they are written. What the yml still carries is the {@code ${MC_TERMINAL_*:}}
   * placeholders, which exist only to spell the underscored variable names, and which resolve
   * to empty when nothing sets them.
   */
  @Test
  void anUnsetVariableResolvesToEmptyAndBindsAsTheRecordDefault() {
    TerminalProperties bound = bind(Map.of(
        "mc.terminal.max-sessions", "",
        "mc.terminal.max-sessions-per-client", "",
        "mc.terminal.idle-timeout", "",
        "mc.terminal.session-max-lifetime", "",
        "mc.terminal.heartbeat-interval", "",
        "mc.terminal.pong-timeout", ""));

    assertEquals(50, bound.maxSessions());
    assertEquals(5, bound.maxSessionsPerClient());
    assertEquals(Duration.ofMinutes(30), bound.idleTimeout());
    assertEquals(Duration.ofHours(8), bound.sessionMaxLifetime());
    assertEquals(Duration.ofSeconds(30), bound.heartbeatInterval());
    assertEquals(Duration.ofSeconds(90), bound.pongTimeout());
  }

  /**
   * A set variable still wins, through the placeholder the yml keeps for exactly that.
   */
  @Test
  void aSetVariableOverridesTheDefault() {
    assertEquals(2, bind(Map.of("mc.terminal.max-sessions", "2")).maxSessions());
  }

  /**
   * {@code user} has no yml line at all: it carries no dash, so relaxed binding reaches
   * MC_TERMINAL_USER straight from the environment. Worth proving rather than assuming — the
   * difference between the default holding and not is a web shell writing /opt/data as root.
   */
  @Test
  void theExecUserBindsFromTheEnvironmentWithNoPlaceholderToHelpIt() {
    assertEquals("hermes", bindEnv(Map.of()).user());
    assertEquals("root", bindEnv(Map.of("MC_TERMINAL_USER", "root")).user());
    // and an operator who blanks it still gets the image default, not the fallback back
    assertEquals("", bindEnv(Map.of("MC_TERMINAL_USER", "")).user());
  }

  private static TerminalProperties bind(Map<String, Object> properties) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
    return Binder.get(environment).bindOrCreate("mc.terminal", TerminalProperties.class);
  }

  /** Bound the way the running app binds it: through the system-environment source, whose
   *  relaxed name mapping is the thing under test. */
  private static TerminalProperties bindEnv(Map<String, Object> variables) {
    StandardEnvironment environment = new StandardEnvironment();
    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    return Binder.get(environment).bindOrCreate("mc.terminal", TerminalProperties.class);
  }
}
