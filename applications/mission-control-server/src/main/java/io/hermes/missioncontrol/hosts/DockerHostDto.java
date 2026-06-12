package io.hermes.missioncontrol.hosts;

/** Matches the frontend DockerHost model. */
public record DockerHostDto(
    String id,
    String name,
    String url,
    String kind,          // local | remote
    String status,        // connected | connecting | error | disconnected
    String engine,
    String apiVersion,
    Long latencyMs,
    String note) {
}
