package io.hermes.missioncontrol.models;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Curated model lists, bound from the {@code mc.models} block in application.yml. Each value is
 * a comma-separated list of model ids. Written there literally rather than behind an env
 * placeholder: the background refresh replaces every one of these on first contact, so a
 * deploy-time override was a knob with nothing to turn it.
 *
 * @param anthropic  default Anthropic model ids
 * @param openai     default OpenAI model ids
 * @param nous       seed Nous Portal model ids; its list endpoint is keyless, so the
 *                   background refresh supersedes this
 * @param openrouter default OpenRouter model ids (provider/model form)
 * @param nvidia     default NVIDIA NIM model ids (publisher/model form)
 */
@ConfigurationProperties(prefix = "mc.models")
public record ModelCatalogProperties(
    String anthropic, String openai, String nous, String openrouter, String nvidia) {
}
