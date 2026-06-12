package io.hermes.missioncontrol.hermes;

public record McpServerDto(
    String id,
    String name,
    String transport,
    String status,
    int tools,
    Long latencyMs) {
}
