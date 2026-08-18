import { ApiModelProvider } from '../hermes-api';

/** Bootstrap mirror of the backend model-provider registry
 *  (ModelProviderRegistry.java) — the picker uses this until the live
 *  `GET /api/providers` resolves. Keep in sync with the Java registry; the
 *  backend is authoritative once it answers. */
export const DEFAULT_LLM_PROVIDERS: ApiModelProvider[] = [
  { key: 'nous', label: 'Nous (account)', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'openrouter', label: 'OpenRouter', needsKey: true, oauth: false, hasCatalog: true, envVar: 'OPENROUTER_API_KEY' },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true, envVar: 'ANTHROPIC_API_KEY' },
  { key: 'openai', label: 'OpenAI', needsKey: true, oauth: false, hasCatalog: true, envVar: 'OPENAI_API_KEY' },
  { key: 'gemini', label: 'Google AI Studio', needsKey: true, oauth: false, hasCatalog: false, envVar: 'GOOGLE_API_KEY' },
  { key: 'xai', label: 'xAI / Grok', needsKey: true, oauth: false, hasCatalog: false, envVar: 'XAI_API_KEY' },
  { key: 'deepseek', label: 'DeepSeek', needsKey: true, oauth: false, hasCatalog: false, envVar: 'DEEPSEEK_API_KEY' },
  { key: 'nvidia', label: 'NVIDIA NIM', needsKey: true, oauth: false, hasCatalog: false, envVar: 'NVIDIA_API_KEY' },
  { key: 'zai', label: 'Z.AI / GLM', needsKey: true, oauth: false, hasCatalog: false, envVar: 'GLM_API_KEY' },
  { key: 'kimi-coding', label: 'Kimi / Moonshot', needsKey: true, oauth: false, hasCatalog: false, envVar: 'KIMI_API_KEY' },
  { key: 'minimax', label: 'MiniMax', needsKey: true, oauth: false, hasCatalog: false, envVar: 'MINIMAX_API_KEY' },
  { key: 'stepfun', label: 'StepFun', needsKey: true, oauth: false, hasCatalog: false, envVar: 'STEPFUN_API_KEY' },
];

/** Offline model lists mirroring the server's `mc.models` catalog defaults
 *  (application.yml) — the fallback when the backend (or the provider API) is
 *  unreachable. Keep in sync with `MC_MODELS_*`. */
export const FALLBACK_MODELS: Record<string, string[]> = {
  nous: ['Hermes-4-405B', 'Hermes-4-70B', 'Hermes-4-14B'],
  openrouter: ['nousresearch/hermes-4-405b', 'anthropic/claude-opus-4.7', 'anthropic/claude-sonnet-4', 'openai/gpt-5.2', 'google/gemini-2.5-pro', 'deepseek/deepseek-chat'],
  anthropic: ['claude-fable-5', 'claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5-20251001'],
  openai: ['gpt-5.2', 'gpt-5.2-mini', 'gpt-5.1', 'gpt-4.1'],
};
