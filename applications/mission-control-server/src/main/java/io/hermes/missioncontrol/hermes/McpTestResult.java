package io.hermes.missioncontrol.hermes;

/** Result of a single MCP server reachability probe. */
public record McpTestResult(
    String name,
    String status,
    int tools,
    Long latencyMs,
    String error) {
}
