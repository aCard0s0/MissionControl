package io.hermes.missioncontrol.mcp;

public record RetainedResourceDto(
    String id,
    String serverId,
    String serverName,
    String hostId,
    String type,
    String name,
    long createdAt) {}
