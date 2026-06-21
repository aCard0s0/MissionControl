/** The dropdown-option prefix for a registered ollama instance (`ollama: <name>`). */
export const OLLAMA_PREFIX = 'ollama: ';

/**
 * Resolve a stored `ollama` + baseUrl back to a selectable `ollama: <name>` option.
 * Templates persist ollama as a bare `ollama` provider plus a baseUrl, but the
 * picker lists one option per registered instance — match the baseUrl to an
 * instance (ignoring a trailing `/v1` and slashes), falling back to the first
 * instance, so a prefilled provider and model never end up mismatched. Returns
 * null when there are no registered ollama instances.
 *
 * Shared by the create-agent picker (agents.ts) and the template editor
 * (agent-profiles.ts) so the resolution rule lives in exactly one place.
 */
export function ollamaOptionForBaseUrl(
  baseUrl: string,
  instances: ReadonlyArray<{ name: string; url: string }>,
): string | null {
  const url = (baseUrl || '').replace(/\/v1\/?$/, '').replace(/\/+$/, '');
  const match = instances.find(p => p.url.replace(/\/+$/, '') === url) ?? instances[0];
  return match ? OLLAMA_PREFIX + match.name : null;
}
