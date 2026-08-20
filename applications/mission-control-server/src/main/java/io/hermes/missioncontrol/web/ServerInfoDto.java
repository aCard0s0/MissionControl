package io.hermes.missioncontrol.web;

/**
 * What the Server Logs page header reports about the dashboard's own process.
 *
 * @param version   the running server version, as {@code /health} also reports
 * @param retained  how many lines the in-memory ring holds before the oldest fall out
 * @param startedAt JVM start, epoch millis — the page renders it as an uptime
 */
public record ServerInfoDto(String version, int retained, long startedAt) {}
