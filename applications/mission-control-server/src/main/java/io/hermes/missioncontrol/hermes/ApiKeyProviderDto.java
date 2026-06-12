package io.hermes.missioncontrol.hermes;

public record ApiKeyProviderDto(
    String label,
    boolean ok,
    String status) {
}
