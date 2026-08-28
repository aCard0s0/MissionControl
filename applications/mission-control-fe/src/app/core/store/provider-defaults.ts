/** Offline model lists mirroring the server's `mc.models` catalog defaults
 *  (application.yml) — the fallback when the backend (or the provider API) is
 *  unreachable. Keep in sync with `MC_MODELS_*`. */
export const FALLBACK_MODELS: Record<string, string[]> = {
  nous: ['Hermes-4-405B', 'Hermes-4-70B', 'Hermes-4-14B'],
  openrouter: ['nousresearch/hermes-4-405b', 'anthropic/claude-opus-4.7', 'anthropic/claude-sonnet-4', 'openai/gpt-5.2', 'google/gemini-2.5-pro', 'deepseek/deepseek-chat'],
  anthropic: ['claude-fable-5', 'claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5-20251001'],
  openai: ['gpt-5.2', 'gpt-5.2-mini', 'gpt-5.1', 'gpt-4.1'],
};
