package io.hermes.missioncontrol.hermes;

public record ApiKeyStatusDto(
    String label,
    String envVar,
    boolean set,
    String masked) {
}
