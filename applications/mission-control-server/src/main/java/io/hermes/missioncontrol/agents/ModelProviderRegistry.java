package io.hermes.missioncontrol.agents;

import java.util.List;
import java.util.Locale;

/**
 * The model providers offered in the create-agent / template UIs and the single
 * source of truth for provider → API-key env var mapping. Keys and env vars
 * mirror hermes' own provider records (hermes_cli/auth.py); ollama instances are
 * handled separately (registered per-container, not listed here).
 *
 * <p>{@code hasCatalog} marks the providers Mission Control can list models for
 * ({@link io.hermes.missioncontrol.models.ModelCatalogService}); the rest take a
 * free-text model id in the UI. {@code oauth} providers (Nous Portal) need no API
 * key — they authenticate out-of-band via {@code hermes portal}.
 */
public final class ModelProviderRegistry {

  public record Provider(String key, String label, String envVar, boolean oauth, boolean hasCatalog) {
    /** True when the UI must collect an API key (key-based, non-OAuth providers). */
    public boolean needsKey() {
      return envVar != null && !oauth;
    }
  }

  private ModelProviderRegistry() {}

  /** Ordered for the picker — Nous first (the default account). */
  public static final List<Provider> PROVIDERS = List.of(
      new Provider("nous", "Nous (account)", null, true, true),
      new Provider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", false, true),
      new Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", false, true),
      new Provider("openai", "OpenAI", "OPENAI_API_KEY", false, true),
      new Provider("gemini", "Google AI Studio", "GOOGLE_API_KEY", false, false),
      new Provider("xai", "xAI / Grok", "XAI_API_KEY", false, false),
      new Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", false, false),
      new Provider("nvidia", "NVIDIA NIM", "NVIDIA_API_KEY", false, false),
      new Provider("zai", "Z.AI / GLM", "GLM_API_KEY", false, false),
      new Provider("kimi-coding", "Kimi / Moonshot", "KIMI_API_KEY", false, false),
      new Provider("minimax", "MiniMax", "MINIMAX_API_KEY", false, false),
      new Provider("stepfun", "StepFun", "STEPFUN_API_KEY", false, false));

  public static Provider byKey(String key) {
    if (key == null) return null;
    String k = key.trim().toLowerCase(Locale.ROOT);
    for (Provider p : PROVIDERS) {
      if (p.key().equals(k)) return p;
    }
    return null;
  }

  /** The API-key env var for a provider, or null for OAuth/unknown/keyless. */
  public static String envVar(String key) {
    Provider p = byKey(key);
    return p == null ? null : p.envVar();
  }
}
