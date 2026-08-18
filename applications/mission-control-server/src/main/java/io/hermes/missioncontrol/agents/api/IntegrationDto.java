package io.hermes.missioncontrol.agents.api;

public record IntegrationDto(
    String kind,
    String status,
    String detail) {
}
