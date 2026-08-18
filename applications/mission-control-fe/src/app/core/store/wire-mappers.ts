import {
  ApiAgentProfile, ApiImageTags, ApiMcpCatalogServer, ApiProfileTemplate,
} from '../hermes-api';
import { AgentProfile, ImageCatalog, McpCatalogServer, ProfileTemplate } from '../models';

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

/** Keeps tolerance for additive backend fields and older rows in one place. */
export function toMcpCatalogServer(api: ApiMcpCatalogServer): McpCatalogServer {
  const runtime = String(api.runtimeState ?? 'unknown').toLowerCase();
  const check = String(api.checkStatus ?? 'unknown').toLowerCase();
  return {
    ...api,
    name: api.name ?? '',
    description: api.description ?? '',
    kind: api.kind ?? 'external',
    hostId: api.hostId || null,
    transport: api.transport ?? (api.kind === 'stdio' ? 'stdio' : 'http'),
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
    headers: (api.headers ?? []).map(e => ({ ...e, secret: !!e.secret })),
    environment: (api.environment ?? []).map(e => ({ ...e, secret: !!e.secret })),
    volumes: api.volumes ?? [],
    healthcheck: api.healthcheck ?? null,
    supportServices: api.supportServices ?? [],
    desiredState: String(api.desiredState).toLowerCase() === 'running' ? 'running' : 'stopped',
    runtimeState: (['running', 'stopped', 'missing', 'error'].includes(runtime) ? runtime : 'unknown') as McpCatalogServer['runtimeState'],
    operationState: String(api.operationState ?? 'idle').toLowerCase(),
    operationError: api.operationError ?? null,
    checkStatus: (['checking', 'connected', 'error'].includes(check) ? check : 'unknown') as McpCatalogServer['checkStatus'],
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

export function toImageCatalog(r: ApiImageTags): ImageCatalog {
  // a backend without `entries` only ever reported local tags, so treat them as pulled
  const pulled = new Set(r.entries?.filter(e => e.pulled).map(e => e.tag) ?? r.tags);
  return {
    repository: r.repository,
    tags: (r.entries?.map(e => e.tag) ?? r.tags).map(tag => ({ tag, pulled: pulled.has(tag) })),
    registryStatus: r.registryStatus ?? 'unavailable',
    fetchedAt: Date.now(),
  };
}

export function toProfileTemplate(api: ApiProfileTemplate): ProfileTemplate {
  return {
    id: api.id,
    name: api.name,
    description: api.description ?? '',
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
