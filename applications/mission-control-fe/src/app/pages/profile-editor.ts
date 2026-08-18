import {
  McpCatalogServer, ModelProvider, ProfileTemplate, ProfileTemplateInput, TemplateMcp,
} from '../core/models';
import { quoteMcpArgs } from '../shared/mcp-args';
import { providerNameOf, resolveProviderOption } from '../shared/provider-resolve';

// The Agent Profiles editor, minus the UI: what a draft is, how a stored template
// becomes one, what makes it saveable, and what the backend gets on save. Pure
// functions, so every rule here is testable without rendering the page.

/** A key being edited. A stored secret arrives with an empty `value`. */
export interface SecretRow {
  key: string;
  value: string;
  set: boolean;
  recoverable: boolean;
}

/** The source id exists only in an editor request. The backend resolves it to a
 * detached, encrypted template snapshot and never persists the catalog link. */
export interface EditorTemplateMcp extends TemplateMcp {
  sourceServerId?: string;
}

/** The form state behind the editor pane. `id` is null for a new template. */
export interface ProfileDraft {
  id: string | null;
  name: string;
  description: string;
  /** a provider *option* (`ollama: <name>`), flattened on save */
  provider: string;
  model: string;
  baseUrl: string;
  cwd: string;
  soul: string;
  memory: string;
  skills: string[];
  mcpServers: EditorTemplateMcp[];
  secrets: SecretRow[];
}

const DEFAULT_CWD = '/opt/data';
const DEFAULT_PROVIDER = 'nous';
const DEFAULT_MODEL = 'Hermes-4-405B';

/** Skill ids / env keys the backend accepts — mirror PROFILE_NAME / ENV_KEY there
 *  (ENV_KEY caps at 64 chars to match the server, so the editor rejects what a
 *  save would). */
const SKILL_ID = /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/;
const ENV_KEY = /^[A-Z][A-Z0-9_]{1,63}$/;
const PROFILE_NAME = /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/;

/** A deploy applies skills and rolls the whole agent back on a bad id, so the
 *  editor refuses one here instead. */
export function skillIdValid(id: string): boolean {
  return SKILL_ID.test(id);
}

export function envKeyValid(key: string): boolean {
  return ENV_KEY.test(key);
}

/** Whether the draft can be saved. Only the name is required — everything else
 *  a template carries is optional, and a blank one still deploys. */
export function profileDraftValid(draft: ProfileDraft): boolean {
  const name = draft.name.trim();
  return !!name && PROFILE_NAME.test(name);
}

/** The draft a fresh "+ new profile" starts from, seeded with the two files an
 *  operator is expected to fill in. */
export function newProfileDraft(): ProfileDraft {
  return {
    id: null,
    name: '',
    description: '',
    provider: DEFAULT_PROVIDER,
    model: DEFAULT_MODEL,
    baseUrl: '',
    cwd: DEFAULT_CWD,
    soul: '# SOUL.md\n\nDescribe this agent\'s personality and directives.\n',
    memory: '# MEMORY.md\n\n',
    skills: [],
    mcpServers: [],
    secrets: [],
  };
}

/**
 * Loads a stored template into the editor. `providerOption` is the dropdown entry
 * the stored provider resolves to — the caller knows the registered ollama
 * instances, and a template stores ollama flat.
 *
 * Every nested value is copied, so abandoning an edit cannot mutate the store's
 * copy, and stored secret values are never read back into the form.
 */
export function profileDraftFrom(template: ProfileTemplate, providerOption: string): ProfileDraft {
  return {
    id: template.id,
    name: template.name,
    description: template.description,
    provider: providerOption,
    model: template.model,
    baseUrl: template.baseUrl,
    cwd: template.cwd || DEFAULT_CWD,
    soul: template.soul,
    memory: template.memory,
    skills: [...template.skills],
    mcpServers: template.mcpServers.map(detachedTemplateMcp),
    secrets: template.secrets.map(secret => ({
      key: secret.key, value: '', set: secret.set, recoverable: secret.recoverable,
    })),
  };
}

/**
 * What the backend gets on save. An `ollama: <name>` option is stored flat — a
 * bare `ollama` plus that instance's endpoint — unless the operator typed an
 * endpoint of their own, which wins.
 */
export function profileDraftToInput(
  draft: ProfileDraft, ollamaInstances: readonly ModelProvider[],
): ProfileTemplateInput {
  const resolved = resolveProviderOption(draft.provider, ollamaInstances);
  return {
    name: draft.name.trim(),
    description: draft.description.trim(),
    provider: providerNameOf(draft.provider),
    model: draft.model.trim(),
    baseUrl: draft.baseUrl.trim() || resolved?.baseUrl || '',
    cwd: draft.cwd.trim(),
    soul: draft.soul,
    memory: draft.memory,
    skills: draft.skills,
    mcpServers: draft.mcpServers,
    secrets: draft.secrets.map(secret => ({ key: secret.key, value: secret.value })),
  };
}

/** Builds the request preview for a one-time catalog snapshot. The backend
 * resolves the source id again and owns the authoritative secret copy. */
export function catalogTemplateSnapshot(
  server: McpCatalogServer, alias: string,
): EditorTemplateMcp | null {
  if (server.kind === 'stdio') {
    if (!server.stdioCommand) return null;
    return {
      name: alias,
      transport: 'stdio',
      command: server.stdioCommand,
      args: quoteMcpArgs(server.args),
      enabled: true,
      sourceServerId: server.id,
    };
  }
  const url = server.crossHostUrl || server.connectionUrl || server.url;
  if (!url) return null;
  return {
    name: alias,
    transport: server.transport,
    url,
    enabled: true,
    sourceServerId: server.id,
  };
}

/** Copy only the durable template fields, deliberately dropping request-only
 *  catalog metadata from an editor row after a successful save. */
export function detachedTemplateMcp(m: TemplateMcp): EditorTemplateMcp {
  return {
    name: m.name,
    transport: m.transport,
    url: m.url,
    command: m.command,
    args: m.args,
    enabled: m.enabled,
  };
}
