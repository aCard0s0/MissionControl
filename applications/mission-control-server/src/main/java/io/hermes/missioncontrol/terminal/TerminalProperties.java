package io.hermes.missioncontrol.terminal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Terminal session limits, bound from MC_TERMINAL_* environment variables via
 * application.yml placeholders. Each terminal is a live {@code docker exec}
 * stream holding JVM-side reader/writer threads, so these bounds keep abandoned
 * or runaway sessions from accumulating threads (the cause of the CPU runaway).
 *
 * <p>The compact constructor supplies defaults for any field left null, so a
 * missing or partial {@code mc.terminal} block still yields a sane config.
 *
 * @param maxSessions          global cap on concurrent terminals
 * @param maxSessionsPerClient per remote-address cap
 * @param idleTimeout          no client activity within this window → reap
 * @param sessionMaxLifetime   absolute lifetime ceiling → reap
 * @param heartbeatInterval    ping cadence / reaper tick
 * @param pongTimeout          no pong within this of the last ping → dead browser, reap
 * @param user                 container user the shell runs as; blank keeps Docker's default
 */
@ConfigurationProperties(prefix = "mc.terminal")
public record TerminalProperties(
    Integer maxSessions,
    Integer maxSessionsPerClient,
    Duration idleTimeout,
    Duration sessionMaxLifetime,
    Duration heartbeatInterval,
    Duration pongTimeout,
    String user) {

  public TerminalProperties {
    if (maxSessions == null) maxSessions = 50;
    if (maxSessionsPerClient == null) maxSessionsPerClient = 5;
    if (idleTimeout == null) idleTimeout = Duration.ofMinutes(30);
    if (sessionMaxLifetime == null) sessionMaxLifetime = Duration.ofHours(8);
    if (heartbeatInterval == null) heartbeatInterval = Duration.ofSeconds(30);
    if (pongTimeout == null) pongTimeout = Duration.ofSeconds(90);
    // `hermes` rather than the image default: every other exec Mission Control runs against a
    // profile goes through DockerExecService as that user, and a root shell writing into
    // /opt/data leaves files the agent itself can no longer read. An image without the user
    // sets MC_TERMINAL_USER to empty, which keeps Docker's default.
    if (user == null) user = "hermes";
  }
}
