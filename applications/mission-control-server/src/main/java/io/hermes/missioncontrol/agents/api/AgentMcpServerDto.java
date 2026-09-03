package io.hermes.missioncontrol.agents.api;

public record AgentMcpServerDto(
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

  /** A server read directly from Hermes config is custom until the profile read
   * overlays a dashboard-owned catalog link — see {@code agents/CatalogLinkOverlay}. */
  public AgentMcpServerDto(
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

  public AgentMcpServerDto linkedTo(String serverId, long synced, long current) {
    return new AgentMcpServerDto(
        id, name, transport, enabled, status, tools, latencyMs, error, checkedAt,
        url, command, args, "catalog", serverId, synced, current, synced < current);
  }
}
