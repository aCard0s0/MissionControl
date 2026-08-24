import {
  ApiAgentProfile, ApiImageTags, ApiMcpCatalogServer, ApiMcpConfigEntry, ApiMcpHealthcheck,
  ApiMcpRetainedResource, ApiMcpSupportService, ApiProfileTemplate, ApiPrompt,
} from '../hermes-api';
import {
  AgentProfile, ImageCatalog, McpCatalogKind, McpCatalogServer, McpCheckStatus, McpConfigEntry,
  McpHealthcheck, McpRetainedResource, McpRuntimeState, McpSupportService, McpTransport,
  ProfileTemplate, Prompt,
} from '../models';

// Backend payload → domain model. Pure functions, deliberately tolerant of
// additive fields and of older rows written by a previous backend version: this
// is the only layer allowed to know that the wire is looser than the model.

export function toAgentProfile(api: ApiAgentProfile): AgentProfile {
  return {
    id: api.id,
    containerId: api.containerId,
    name: api.name,
    role: api.role,
    state: api.state,
    provider: api.provider,
    model: api.model,
    apiKeyMasked: api.apiKeyMasked || '',
    cwd: api.cwd,
    soul: api.soul,
    memoryMd: api.memoryMd,
    configYaml: api.configYaml,
    skills: (api.skills ?? []).map(s => ({
      id: s.id,
      name: s.name,
      source: s.source as AgentProfile['skills'][number]['source'],
      version: s.version,
      description: s.description,
      enabled: !!s.enabled,
    })),
    mcp: (api.mcp ?? []).map(m => ({
      id: m.id,
      name: m.name,
      transport: m.transport as AgentProfile['mcp'][number]['transport'],
      enabled: m.enabled !== false,
      origin: m.origin === 'catalog' ? 'catalog' : 'custom',
      catalogServerId: m.catalogServerId ?? null,
      syncedRevision: m.syncedRevision ?? null,
      catalogRevision: m.catalogRevision ?? null,
      updateAvailable: !!m.updateAvailable,
      status: m.status as AgentProfile['mcp'][number]['status'],
      tools: m.tools,
      latencyMs: m.latencyMs,
      error: m.error ?? null,
      checkedAt: m.checkedAt ?? null,
      url: m.url ?? undefined,
      command: m.command ?? undefined,
      args: m.args ?? undefined,
    })),
    integrations: (api.integrations ?? []).map(i => ({
      kind: i.kind as AgentProfile['integrations'][number]['kind'],
      status: i.status as AgentProfile['integrations'][number]['status'],
      detail: i.detail,
    })),
    sessions: [],
    msgsToday: 0,
    tokensToday: 0,
    errorRate: 0,
    lastActive: api.lastActive,
  };
}

const KINDS: McpCatalogKind[] = ['managed', 'external', 'stdio'];
const TRANSPORTS: McpTransport[] = ['stdio', 'http', 'sse'];
const RUNTIME_STATES: McpRuntimeState[] = ['running', 'stopped', 'missing', 'error'];
const CHECK_STATUSES: McpCheckStatus[] = ['checking', 'connected', 'error'];

/** One of `allowed`, matched case-insensitively, or `fallback`. The backend
 *  spells its enums in mixed case and can name a state this build predates. */
function oneOf<T extends string>(value: string | undefined, allowed: T[], fallback: T): T {
  const wanted = String(value ?? '').toLowerCase();
  return allowed.find(item => item === wanted) ?? fallback;
}

function toConfigEntry(entry: ApiMcpConfigEntry): McpConfigEntry {
  return {
    key: entry.key,
    value: entry.value ?? null,
    secret: !!entry.secret,
    set: entry.set,
    recoverable: entry.recoverable,
  };
}

function toHealthcheck(api: ApiMcpHealthcheck | null | undefined): McpHealthcheck | null {
  return api ? { ...api, test: [...api.test] } : null;
}

/**
 * The list fields stay absent when the row omits them rather than becoming empty
 * arrays: the editor sends this shape back on save, and an empty `entrypoint` or
 * `command` is a Compose override that clears the image's own — which is not
 * what a service that never named one meant.
 */
function toSupportService(api: ApiMcpSupportService): McpSupportService {
  return {
    name: api.name,
    image: api.image,
    platform: api.platform ?? null,
    entrypoint: api.entrypoint,
    command: api.command,
    environment: api.environment?.map(toConfigEntry),
    volumes: api.volumes?.map(volume => ({ ...volume })),
    healthcheck: toHealthcheck(api.healthcheck),
  };
}

/**
 * Keeps tolerance for older rows in one place. Every field is named: the wire
 * type is deliberately looser than the model, so a field this build does not
 * know about stays out of the store rather than riding in on a spread, and a
 * field the backend stops sending fails here instead of reaching a template as
 * undefined.
 */
export function toMcpCatalogServer(api: ApiMcpCatalogServer): McpCatalogServer {
  const kind = oneOf(api.kind, KINDS, 'external');
  return {
    id: api.id,
    name: api.name ?? '',
    description: api.description ?? '',
    kind,
    hostId: api.hostId || null,
    transport: oneOf(api.transport, TRANSPORTS, kind === 'stdio' ? 'stdio' : 'http'),
    url: api.url || null,
    image: api.image || null,
    platform: api.platform || null,
    entrypoint: api.entrypoint ?? [],
    command: api.command ?? [],
    stdioCommand: api.stdioCommand || null,
    args: api.args ?? [],
    internalPort: api.internalPort ?? null,
    publishedPort: api.publishedPort ?? null,
    path: api.path || null,
    crossHostUrl: api.crossHostUrl || null,
    connectionUrl: api.connectionUrl || null,
    headers: (api.headers ?? []).map(toConfigEntry),
    environment: (api.environment ?? []).map(toConfigEntry),
    volumes: (api.volumes ?? []).map(volume => ({ ...volume })),
    healthcheck: toHealthcheck(api.healthcheck),
    supportServices: (api.supportServices ?? []).map(toSupportService),
    desiredState: oneOf(api.desiredState, ['running', 'stopped'], 'stopped'),
    runtimeState: oneOf(api.runtimeState, RUNTIME_STATES, 'unknown'),
    operationState: String(api.operationState ?? 'idle').toLowerCase(),
    operationError: api.operationError ?? null,
    checkStatus: oneOf(api.checkStatus, CHECK_STATUSES, 'unknown'),
    checkError: api.checkError ?? null,
    checkedAt: api.checkedAt ?? null,
    latencyMs: api.latencyMs ?? null,
    revision: api.revision ?? 1,
    appliedRevision: api.appliedRevision ?? 0,
    pendingChanges: !!api.pendingChanges,
    serviceKey: api.serviceKey || null,
    createdAt: api.createdAt ?? Date.now(),
    updatedAt: api.updatedAt ?? Date.now(),
  };
}

/** A retained volume is rendered straight from this, so every label it shows
 *  has to survive a row that omits it. */
export function toMcpRetainedResource(api: ApiMcpRetainedResource): McpRetainedResource {
  return {
    id: api.id,
    serverId: api.serverId ?? null,
    serverName: api.serverName ?? '',
    hostId: api.hostId ?? '',
    type: api.type ?? 'volume',
    name: api.name ?? '',
    createdAt: api.createdAt ?? 0,
  };
}

export function toImageCatalog(r: ApiImageTags): ImageCatalog {
  // a backend without `entries` only ever reported local tags, so treat them as pulled
  const pulled = new Set(r.entries?.filter(e => e.pulled).map(e => e.tag) ?? r.tags);
  // a backend without `entries` reports no digests either, so those tags compare as unknown
  const digests = new Map((r.entries ?? []).map(e => [e.tag, e.digest ?? null]));
  return {
    repository: r.repository,
    tags: (r.entries?.map(e => e.tag) ?? r.tags).map(tag =>
      ({ tag, pulled: pulled.has(tag), digest: digests.get(tag) ?? null })),
    registryStatus: r.registryStatus ?? 'unavailable',
    fetchedAt: Date.now(),
  };
}

export function toProfileTemplate(api: ApiProfileTemplate): ProfileTemplate {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
    // a blueprint from before categories existed reads as the default rather than as
    // a blank chip of its own; saving it files it there for real
    category: api.category || 'general',
    provider: api.provider ?? '',
    model: api.model ?? '',
    baseUrl: api.baseUrl ?? '',
    cwd: api.cwd ?? '',
    soul: api.soul ?? '',
    memory: api.memory ?? '',
    skills: api.skills ?? [],
    mcpServers: (api.mcpServers ?? []).map(m => ({
      name: m.name, transport: m.transport, url: m.url, command: m.command, args: m.args,
      enabled: m.enabled !== false,
    })),
    secrets: (api.secrets ?? []).map(s => ({ key: s.key, set: !!s.set, recoverable: !!s.recoverable })),
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}

/** A prompt's optional columns are null on the wire and '' / [] in the model, so a
 *  template never has to ask which of the two an empty note is. */
export function toPrompt(api: ApiPrompt): Prompt {
  return {
    id: api.id,
    title: api.title,
    body: api.body ?? '',
    category: api.category || 'general',
    notes: api.notes ?? '',
    tags: api.tags ?? [],
    createdAt: api.createdAt,
    updatedAt: api.updatedAt,
  };
}
