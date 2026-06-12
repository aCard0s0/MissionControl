package io.hermes.missioncontrol;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration, bound from MC_* environment variables via
 * application.yml placeholders.
 *
 * @param dataMode        'live' (default) or 'mock' — forwarded to the frontend via /config.js
 * @param apiBaseUrl      backend base url for the frontend; empty = same origin
 * @param dockerSocket    default local daemon endpoint
 * @param hermesImage     image used when deploying Hermes containers
 * @param containerFilter substring that marks a container as Hermes-related
 * @param version         server version reported by /health
 */
@ConfigurationProperties(prefix = "mc")
public record AppProperties(
    String dataMode,
    String apiBaseUrl,
    String dockerSocket,
    String hermesImage,
    String containerFilter,
    String version) {
}
