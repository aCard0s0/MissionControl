package io.hermes.missioncontrol.models;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Curated model lists, bound from MC_MODELS_* environment variables via
 * application.yml placeholders. Each value is a comma-separated list of
 * model ids.
 *
 * @param anthropic default Anthropic model ids
 * @param openai    default OpenAI model ids
 */
@ConfigurationProperties(prefix = "mc.models")
public record ModelCatalogProperties(String anthropic, String openai) {
}
