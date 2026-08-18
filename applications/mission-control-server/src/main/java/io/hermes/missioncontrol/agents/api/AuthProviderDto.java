package io.hermes.missioncontrol.agents.api;

public record AuthProviderDto(
    String label,
    boolean ok,
    String status,
    String hint) {
}
