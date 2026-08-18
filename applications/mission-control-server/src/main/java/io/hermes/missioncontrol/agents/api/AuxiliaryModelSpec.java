package io.hermes.missioncontrol.agents.api;


/**
 * An optional override for the model hermes' auxiliary side tasks run on —
 * compression, summarization, memory flush and the other slots listed in
 * {@code HermesModelConfig.AUXILIARY_TASKS}.
 *
 * <p>These tasks default to the profile's main model, which is usually what you
 * want and always what you get when this is absent. An override earns its keep
 * when the main model is expensive or slow: side tasks are frequent, short and
 * mechanical, so pointing them at a cheaper model cuts cost without touching the
 * quality of the conversation itself.
 *
 * <p>{@code model} is the only load-bearing field. A blank one means "no
 * override" — the whole spec is ignored and the tasks follow the main model. A
 * blank {@code provider} means "same provider as the main model, different model
 * on it", in which case the main endpoint is inherited too.
 *
 * @param provider provider id, or blank to reuse the profile's main provider
 * @param model    the model id side tasks run on; blank disables the override
 * @param baseUrl  custom OpenAI-compatible endpoint, for local/self-hosted models
 * @param apiKey   key for {@code provider}, written to the profile's .env when the
 *                 override introduces a provider the main model does not already
 *                 authenticate
 */
public record AuxiliaryModelSpec(
    String provider,
    String model,
    String baseUrl,
    String apiKey) {
}
