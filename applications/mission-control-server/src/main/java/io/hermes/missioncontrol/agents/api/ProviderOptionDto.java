package io.hermes.missioncontrol.agents.api;

/** A selectable model provider for the create-agent / template UIs. */
public record ProviderOptionDto(
    String key,
    String label,
    boolean needsKey,
    boolean oauth,
    boolean hasCatalog,
    String envVar) {
}
