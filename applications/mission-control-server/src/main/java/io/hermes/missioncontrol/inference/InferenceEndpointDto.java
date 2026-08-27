package io.hermes.missioncontrol.inference;

/** Matches the frontend ModelProvider model. */
public record InferenceEndpointDto(
    String id,
    String name,
    String url,
    String kind,          // ollama
    String status,        // connected | error
    String version,
    String detail) {
}
