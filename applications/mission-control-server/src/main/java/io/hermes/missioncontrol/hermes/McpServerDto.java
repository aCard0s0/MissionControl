package io.hermes.missioncontrol.hermes;

public record McpServerDto(
    String id,
    String name,
    String transport,
    boolean enabled,
    String status,
    int tools,
    Long latencyMs,
    String error,
    Long checkedAt,
    String url,
    String command,
    String args,
    String origin,
    String catalogServerId,
    Long syncedRevision,
    Long catalogRevision,
    boolean updateAvailable) {

  /** A server read directly from Hermes config is custom until the controller
   * overlays a dashboard-owned catalog link. */
  public McpServerDto(
      String id,
      String name,
      String transport,
      boolean enabled,
      String status,
      int tools,
      Long latencyMs,
      String error,
      Long checkedAt,
      String url,
      String command,
      String args) {
    this(id, name, transport, enabled, status, tools, latencyMs, error, checkedAt,
        url, command, args, "custom", null, null, null, false);
  }

  public McpServerDto linkedTo(String serverId, long synced, long current) {
    return new McpServerDto(
        id, name, transport, enabled, status, tools, latencyMs, error, checkedAt,
        url, command, args, "catalog", serverId, synced, current, synced < current);
  }
}
