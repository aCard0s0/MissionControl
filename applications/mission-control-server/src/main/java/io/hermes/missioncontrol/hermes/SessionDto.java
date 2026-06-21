package io.hermes.missioncontrol.hermes;

public record SessionDto(
    String id,
    String title,
    String platform,
    long startedAt,
    int messages,
    String status) {
}
