import {
  BoardColumn, McpCatalogServer, McpRetainedResource, SkillContent, TemplateMcp,
} from '../models';

// Wire shapes for mission-control-server. Everything the backend sends or
// accepts is declared here; the clients under this folder only compose URLs and
// the store maps these onto the domain models in ../models.

export interface ApiContainer {
  id: string;
  shortId: string;
  name: string;
  hostId: string;
  status: 'running' | 'stopped' | 'unhealthy' | 'unknown';
  image: string;
  version: string;
  startedAt: number | null;
  sizeRootFsGb: number | null;
  profiles: string[];
}

export interface ApiStats {
  cpuPercent: number;
  ramMb: number;
  ramTotalMb: number;
  rxBytes: number;
  txBytes: number;
  sampledAt: number;
}

export interface ApiLogLine {
  ts: number;
  level: 'info' | 'warn' | 'error' | 'debug';
  source: string;
  msg: string;
}

export interface ApiBoardTask {
  id: string;
  containerId: string;
  agentId: string | null;
  title: string;
  column: BoardColumn;
  priority: 'low' | 'med' | 'high';
  tags: string[];
  createdAt: number;
}

export interface ApiImageTag {
  tag: string;
  pulled: boolean;
  remote: boolean;
}

export interface ApiImageTags {
  repository: string;
  tags: string[];                      // every known tag, newest first
  entries?: ApiImageTag[];             // same order, with local-store presence
  newest?: string | null;              // newest pinned release, floating tags excluded
  registryStatus?: string;             // ok | cached | unavailable | unsupported | disabled
  registryDetail?: string | null;
}

export interface ApiSkillRef {
  id: string;
  name: string;
  source: 'bundled' | 'user' | 'hub' | string;
  version: string;
  description: string;
  enabled: boolean;
}

/** Wire shape for a skill's SKILL.md + files — identical to the domain
 *  {@link SkillContent}, aliased so the two can't drift. */
export type ApiSkillContent = SkillContent;

export interface ApiMcpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse' | string;
  enabled?: boolean;
  origin?: 'custom' | 'catalog' | string;
  catalogServerId?: string | null;
  syncedRevision?: number | null;
  catalogRevision?: number | null;
  updateAvailable?: boolean;
  status: 'unknown' | 'connected' | 'error' | 'disabled' | string;
  tools: number;
  latencyMs: number | null;
  error?: string | null;
  checkedAt?: number | null;
  url?: string | null;
  command?: string | null;
  args?: string | null;
}

export interface ApiMcpTestResult {
  name: string;
  status: 'unknown' | 'connected' | 'error' | 'disabled' | string;
  tools: number;
  latencyMs: number | null;
  error: string | null;
  checkedAt: number;
}

/** Catalog wire models are deliberately aliases of the domain contracts. All
 *  normalization of optional/legacy backend fields lives in HermesStore. */
export type ApiMcpCatalogServer = McpCatalogServer;
export type ApiMcpRetainedResource = McpRetainedResource;

export interface ApiIntegration {
  kind: string;
  status: 'up' | 'degraded' | 'down' | 'off' | string;
  detail: string;
}

export interface ApiSession {
  id: string;
  title: string;
  platform: string;
  startedAt: number;
  messages: number;
  status: 'open' | 'closed' | string;
}

export interface ApiModelCatalog {
  provider: string;
  models: string[];
  source: 'config' | 'live' | string;
}

export interface ApiPullState {
  model: string;
  status: 'pulling' | 'done' | 'error';
  detail: string | null;
}

/** Optional override for the model hermes' auxiliary side tasks run on
 *  (compression, summarization, memory flush, …). Omitted entirely when the side
 *  tasks should follow the main model, which is the default. A blank `provider`
 *  means "same provider, different model" and inherits the main endpoint. */
export interface ApiAuxiliaryModel {
  provider?: string;
  model: string;
  baseUrl?: string;
  apiKey?: string;
}

export interface ApiAgentProfile {
  id: string;
  containerId: string;
  name: string;
  role: string;
  state: 'active' | 'idle' | 'dormant';
  provider: string;
  model: string;
  apiKeyMasked: string;
  cwd: string;
  soul: string;
  memoryMd: string;
  configYaml: string;
  skills: ApiSkillRef[];
  mcp: ApiMcpServer[];
  integrations: ApiIntegration[];
  lastActive: number;
}

export interface ApiSetupApiKey {
  label: string;
  envVar: string;
  set: boolean;
  masked: string | null;
}

export interface ApiSetupAuthProvider {
  label: string;
  ok: boolean;
  status: string;
  hint: string | null;
}

export interface ApiModelProvider {
  key: string;
  label: string;
  needsKey: boolean;
  oauth: boolean;
  hasCatalog: boolean;
  envVar: string | null;          // API-key env var, or null for OAuth/keyless
}

export interface ApiSetupKeyProvider {
  label: string;
  ok: boolean;
  status: string;
}

export interface ApiSetupMessaging {
  label: string;
  ok: boolean;
  status: string;
  tokenVar: string;
  homeVar: string | null;
  homeChannel: string | null;
}

export interface ApiAgentSetup {
  envPath: string;
  envExists: boolean;
  apiKeys: ApiSetupApiKey[];
  authProviders: ApiSetupAuthProvider[];
  apiKeyProviders: ApiSetupKeyProvider[];
  messaging: ApiSetupMessaging[];
}

export interface ApiTemplateSecret {
  key: string;
  set: boolean;
  recoverable: boolean;
}

export interface ApiProfileTemplate {
  id: string;
  name: string;
  description: string;
  provider: string;
  model: string;
  baseUrl: string;
  cwd: string;
  soul: string;
  memory: string;
  skills: string[];
  mcpServers: TemplateMcp[];
  secrets: ApiTemplateSecret[];
  createdAt: number;
  updatedAt: number;
}
