package io.hermes.missioncontrol.mcp;

public record AgentMcpLink(
    String hostId,
    String containerId,
    String profile,
    String alias,
    String serverId,
    long syncedRevision,
    long createdAt,
    long updatedAt) {}
