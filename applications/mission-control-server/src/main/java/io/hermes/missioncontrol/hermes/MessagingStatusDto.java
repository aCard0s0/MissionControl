package io.hermes.missioncontrol.hermes;

public record MessagingStatusDto(
    String label,
    boolean ok,
    String status,
    String tokenVar,
    String homeVar,
    String homeChannel) {
}
