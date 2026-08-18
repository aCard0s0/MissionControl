package io.hermes.missioncontrol.agents.api;

public record SessionDto(
    String id,
    String title,
    String platform,
    long startedAt,
    int messages,
    String status) {
}
