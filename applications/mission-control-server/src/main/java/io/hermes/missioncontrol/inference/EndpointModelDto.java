package io.hermes.missioncontrol.inference;

/** One installed model as reported by ollama GET /api/tags. */
public record EndpointModelDto(
    String name,
    Long sizeBytes,
    String family,
    String parameterSize,
    Long modifiedAt) {
}
