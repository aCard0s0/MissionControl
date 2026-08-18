package io.hermes.missioncontrol.agents.api;

public record ApiKeyProviderDto(
    String label,
    boolean ok,
    String status) {
}
