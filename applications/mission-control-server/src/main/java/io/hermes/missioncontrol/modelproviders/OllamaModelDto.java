package io.hermes.missioncontrol.modelproviders;

/** One installed model as reported by ollama GET /api/tags. */
public record OllamaModelDto(
    String name,
    Long sizeBytes,
    String family,
    String parameterSize,
    Long modifiedAt) {
}
