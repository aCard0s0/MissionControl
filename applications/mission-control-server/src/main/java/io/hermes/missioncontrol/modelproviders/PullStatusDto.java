package io.hermes.missioncontrol.modelproviders;

/** In-memory state of a model pull triggered through the dashboard. */
public record PullStatusDto(
    String model,
    String status,        // pulling | done | error
    String detail) {
}
