package io.hermes.missioncontrol.hermes;

import java.util.List;

/** An MCP server definition stored in a profile template (no live status). */
public record McpServerSpec(
    String name,
    String transport,
    String url,
    String command,
    String args,
    Boolean enabled,
    /** Input-only catalog id. Resolved to a detached snapshot before storage. */
    String sourceServerId,
    /** Encrypted stdio environment captured from the catalog snapshot. */
    List<TemplateMcpConfigValue> environment,
    /** Encrypted HTTP headers captured from the catalog snapshot. */
    List<TemplateMcpConfigValue> headers) {

  /** Backwards-compatible shape for custom and agent-captured definitions. */
  public McpServerSpec(
      String name,
      String transport,
      String url,
      String command,
      String args,
      Boolean enabled) {
    this(name, transport, url, command, args, enabled, null, null, null);
  }
}
