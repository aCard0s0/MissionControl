package io.hermes.missioncontrol.docker;

/**
 * One tag of the Hermes image. A tag can be known locally, in the registry, or
 * both — 'pulled' is what decides whether deploying it costs a download.
 */
public record ImageTagDto(
    String tag,
    boolean pulled,
    boolean remote,
    Long lastUpdated,   // epoch ms, null unless the registry reported it
    Long sizeBytes,
    String digest) {
}
