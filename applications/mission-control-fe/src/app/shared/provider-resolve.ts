import { LlmProvider, InferenceEndpoint, ProfileTemplate } from '../core/models';

// Which provider a profile runs on is picked from a dropdown, but stored as the
// pair hermes actually needs. Both the create-agent dialog and the profile
// template editor have to agree on that translation, in both directions, so it
// lives here rather than in either page.

/**
 * The dropdown-option prefix for a registered endpoint, and the bare marker it collapses to.
 *
 * <p>Spelled "ollama" for every kind, deliberately. It is NOT a hermes provider key — hermes
 * has no `ollama` provider at all, and drops the name entirely once a `base_url` is set
 * (HermesModelConfig.modelConfigEntries). It is Mission Control's own marker, persisted in
 * saved templates, meaning "an endpoint, resolve it by base url". Renaming it would orphan
 * every stored template for nothing, so a second kind reuses it rather than adding a prefix.
 */
export const OLLAMA_PREFIX = 'ollama: ';

/** What the picker calls each endpoint kind. */
const KIND_LABELS: Record<string, string> = {
  ollama: 'Ollama',
  openai: 'OpenAI-compatible',
};

/** One entry in a provider dropdown. */
export interface ProviderOption {
  value: string;
  label: string;
}

/** Every provider a profile can be pointed at: the LLM vendor registry as the backend
 *  reports it, plus one entry per registered inference endpoint. */
export function providerOptions(
  llm: readonly LlmProvider[],
  endpoints: readonly InferenceEndpoint[],
): ProviderOption[] {
  return [
    ...llm.map(provider => ({ value: provider.key, label: provider.label })),
    ...endpoints.map(endpoint => ({
      value: OLLAMA_PREFIX + endpoint.name,
      label: (KIND_LABELS[endpoint.kind] ?? endpoint.kind) + ': ' + endpoint.name,
    })),
  ];
}

/** The OpenAI-compatible base url hermes talks to an endpoint through — the one surface
 *  every local runtime serves, which is why agents are already runtime-agnostic. */
export function endpointBaseUrl(instance: { url: string }): string {
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
export function endpointOptionForBaseUrl(
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
  instances: readonly InferenceEndpoint[],
): { provider: string; baseUrl?: string } | null {
  if (!option.startsWith(OLLAMA_PREFIX)) return { provider: option };
  const instance = instances.find(p => OLLAMA_PREFIX + p.name === option);
  return instance ? { provider: 'ollama', baseUrl: endpointBaseUrl(instance) } : null;
}

/** The reverse: the dropdown option a stored (provider, baseUrl) pair selects,
 *  or null when nothing in the list matches — the caller then decides whether to
 *  fall back or to leave the picker alone. */
export function providerOptionFor(
  provider: string,
  baseUrl: string,
  options: readonly ProviderOption[],
  instances: readonly InferenceEndpoint[],
): string | null {
  if (options.some(option => option.value === provider)) return provider;
  if (provider === 'ollama') return endpointOptionForBaseUrl(baseUrl, instances);
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
  provider: Pick<LlmProvider, 'envVar'> | null | undefined,
): boolean {
  const envVar = provider?.envVar;
  if (!template || !envVar) return false;
  return template.secrets.some(secret => secret.key === envVar && secret.set && secret.recoverable);
}
