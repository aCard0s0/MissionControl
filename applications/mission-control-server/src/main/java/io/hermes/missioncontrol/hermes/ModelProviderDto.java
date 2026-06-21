package io.hermes.missioncontrol.hermes;

/** A selectable model provider for the create-agent / template UIs. */
public record ModelProviderDto(
    String key,
    String label,
    boolean needsKey,
    boolean oauth,
    boolean hasCatalog,
    String envVar) {
}
