package io.hermes.missioncontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration, bound from MC_* environment variables via
 * application.yml placeholders.
 *
 * @param apiBaseUrl      backend base url for the frontend; empty = same origin
 * @param dockerSocket    default local daemon endpoint
 * @param hermesImage     image used when deploying Hermes containers
 * @param containerFilter substring that marks a container as Hermes-related
 * @param version         server version reported by /health
 * @param startupReconcile whether MCP catalog seeding and reconciliation run at
 *                         startup; false keeps a context test off any daemon
 */
@ConfigurationProperties(prefix = "mc")
public record AppProperties(
    String apiBaseUrl,
    String dockerSocket,
    String hermesImage,
    String containerFilter,
    String version,
    boolean startupReconcile) {
}
