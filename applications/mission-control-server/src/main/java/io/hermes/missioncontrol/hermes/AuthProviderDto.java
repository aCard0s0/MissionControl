package io.hermes.missioncontrol.hermes;

public record AuthProviderDto(
    String label,
    boolean ok,
    String status,
    String hint) {
}
