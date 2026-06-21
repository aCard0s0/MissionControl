package io.hermes.missioncontrol.hermes;

/** An MCP server definition stored in a profile template (no live status). */
public record McpServerSpec(
    String name,
    String transport,
    String url,
    String command,
    String args,
    Boolean enabled) {
}
