import { ApiModelProvider } from '../core/hermes-api';
import { ModelProvider, ProfileTemplate } from '../core/models';

// Which provider a profile runs on is picked from a dropdown, but stored as the
// pair hermes actually needs. Both the create-agent dialog and the profile
// template editor have to agree on that translation, in both directions, so it
// lives here rather than in either page.

/** The dropdown-option prefix for a registered ollama instance (`ollama: <name>`). */
export const OLLAMA_PREFIX = 'ollama: ';

/** One entry in a provider dropdown. */
export interface ProviderOption {
  value: string;
  label: string;
}

/** Every provider a profile can be pointed at: the LLM registry as the backend
 *  reports it, plus one entry per registered ollama instance. */
export function providerOptions(
  llm: readonly ApiModelProvider[],
  ollama: readonly ModelProvider[],
): ProviderOption[] {
  return [
    ...llm.map(provider => ({ value: provider.key, label: provider.label })),
    ...ollama.map(provider => ({
      value: OLLAMA_PREFIX + provider.name,
      label: 'Ollama: ' + provider.name,
    })),
  ];
}

/** The OpenAI-compatible endpoint hermes talks to an ollama instance through. */
export function ollamaBaseUrl(instance: { url: string }): string {
  return instance.url.replace(/\/+$/, '') + '/v1';
}

/**
 * Resolve a stored `ollama` + baseUrl back to a selectable `ollama: <name>` option.
 * Templates persist ollama as a bare `ollama` provider plus a baseUrl, but the
 * picker lists one option per registered instance — match the baseUrl to an
 * instance (ignoring a trailing `/v1` and slashes), falling back to the first
 * instance, so a prefilled provider and model never end up mismatched. Returns
 * null when there are no registered ollama instances.
 */
export function ollamaOptionForBaseUrl(
  baseUrl: string,
  instances: ReadonlyArray<{ name: string; url: string }>,
): string | null {
  const url = (baseUrl || '').replace(/\/v1\/?$/, '').replace(/\/+$/, '');
  const match = instances.find(p => p.url.replace(/\/+$/, '') === url) ?? instances[0];
  return match ? OLLAMA_PREFIX + match.name : null;
}

/** The bare provider name an option is stored as: every ollama option collapses
 *  to `ollama`, whether or not that instance is still registered — a template
 *  may legitimately name an endpoint the operator typed themselves. */
export function providerNameOf(option: string): string {
  return option.startsWith(OLLAMA_PREFIX) ? 'ollama' : option;
}

/**
 * Splits a dropdown option into the (provider, baseUrl) pair the API takes.
 * Ollama is listed one option per registered instance, but hermes stores it as a
 * bare `ollama` plus that instance's endpoint. Returns null when the option names
 * an ollama instance that is no longer registered — there is no endpoint left to
 * send, and guessing another instance would point the profile somewhere the
 * operator did not choose.
 */
export function resolveProviderOption(
  option: string,
  instances: readonly ModelProvider[],
): { provider: string; baseUrl?: string } | null {
  if (!option.startsWith(OLLAMA_PREFIX)) return { provider: option };
  const instance = instances.find(p => OLLAMA_PREFIX + p.name === option);
  return instance ? { provider: 'ollama', baseUrl: ollamaBaseUrl(instance) } : null;
}

/** The reverse: the dropdown option a stored (provider, baseUrl) pair selects,
 *  or null when nothing in the list matches — the caller then decides whether to
 *  fall back or to leave the picker alone. */
export function providerOptionFor(
  provider: string,
  baseUrl: string,
  options: readonly ProviderOption[],
  instances: readonly ModelProvider[],
): string | null {
  if (options.some(option => option.value === provider)) return provider;
  if (provider === 'ollama') return ollamaOptionForBaseUrl(baseUrl, instances);
  return null;
}

/**
 * Whether a template already carries a key that would authenticate `provider` —
 * a secret under its env var that is both stored and still decryptable. A
 * captured template records key names as unset placeholders, so those do not
 * count and the operator is still asked for one.
 */
export function templateProvidesKey(
  template: Pick<ProfileTemplate, 'secrets'> | null | undefined,
  provider: Pick<ApiModelProvider, 'envVar'> | null | undefined,
): boolean {
  const envVar = provider?.envVar;
  if (!template || !envVar) return false;
  return template.secrets.some(secret => secret.key === envVar && secret.set && secret.recoverable);
}
