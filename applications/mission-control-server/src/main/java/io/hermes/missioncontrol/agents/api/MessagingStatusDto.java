package io.hermes.missioncontrol.agents.api;

public record MessagingStatusDto(
    String label,
    boolean ok,
    String status,
    String tokenVar,
    String homeVar,
    String homeChannel) {
}
