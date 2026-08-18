package io.hermes.missioncontrol.agents.api;

public record ApiKeyStatusDto(
    String label,
    String envVar,
    boolean set,
    String masked) {
}
