package io.hermes.missioncontrol.modelproviders;

/** Matches the frontend ModelProvider model. */
public record ModelProviderDto(
    String id,
    String name,
    String url,
    String kind,          // ollama
    String status,        // connected | error
    String version,
    String detail) {
}
