import { BoardColumn, TemplateMcp } from '../models';

// Wire shapes for mission-control-server. Everything the backend sends or
// accepts is declared here; the clients under this folder only compose URLs and
// the store maps these onto the domain models in ../models.

/** A docker daemon, as the backend describes it. Every field past the id is optional on the
 *  wire: a host that has never answered a probe has no engine, version or latency to report. */
export interface ApiDockerHost {
  id: string;
  name?: string;
  url?: string;
  kind?: 'local' | 'remote' | string;
  status?: 'connected' | 'connecting' | 'error' | 'disconnected' | string;
  engine?: string | null;
  apiVersion?: string | null;
  latencyMs?: number | null;
  note?: string | null;
}

/** A registered ollama endpoint. `status` is the last probe's answer, and a provider that has
 *  not been probed yet reports none. */
export interface ApiOllamaProvider {
  id: string;
  name?: string;
  url?: string;
  kind?: 'ollama' | string;
  status?: 'connected' | 'error' | 'unknown' | string;
  version?: string | null;
  detail?: string | null;
}

/** One model on an ollama endpoint, relayed from `GET {url}/api/tags`. Its optional fields are
 *  ollama's own: a model pulled from a bare digest reports no family or parameter size. */
export interface ApiOllamaModel {
  name: string;
  sizeBytes?: number;
  family?: string;
  parameterSize?: string;
  modifiedAt?: number;
}

/** One turn of a recorded session, read out of the agent's own state.db. */
export interface ApiChatMessage {
  role?: string;
  content?: string;
  toolName?: string | null;
  toolCalls?: string | null;
  reasoning?: string | null;
  ts?: number;
}

export interface ApiContainer {
  id: string;
  shortId: string;
  name: string;
  hostId: string;
  status: 'running' | 'stopped' | 'unhealthy' | 'unknown';
  image: string;
  version: string;
  imageDigest: string | null;
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

export interface ApiPrompt {
  id: string;
  title: string;
  body: string;
  category: string;
  notes: string | null;
  tags: string[] | null;
  createdAt: number;
  updatedAt: number;
}

export interface ApiImageTag {
  tag: string;
  pulled: boolean;
  remote: boolean;
  /** Registry manifest digest, when the registry reported one. Compared against a
   *  container's own image digest to tell whether a floating tag has moved on. */
  digest: string | null;
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

/** A skill's SKILL.md body and the files beside it. A backend that lists no
 *  files omits the key rather than sending an empty array. */
export interface ApiSkillContent {
  name: string;
  path: string;
  body: string;
  files?: string[];
}

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

/** An environment variable or HTTP header as the backend sends it. A row written
 *  before secrets were flagged carries no `secret`, and a secret's value never
 *  comes back at all. */
export interface ApiMcpConfigEntry {
  key: string;
  value?: string | null;
  secret?: boolean;
  set?: boolean;
  recoverable?: boolean;
}

export interface ApiMcpNamedVolume {
  name: string;
  target: string;
}

export interface ApiMcpHealthcheck {
  test: string[];
  interval?: string | null;
  timeout?: string | null;
  retries?: number | null;
  startPeriod?: string | null;
}

/** A service the backend renders into the managed stack alongside the server. */
export interface ApiMcpSupportService {
  name: string;
  image: string;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  environment?: ApiMcpConfigEntry[];
  volumes?: ApiMcpNamedVolume[];
  healthcheck?: ApiMcpHealthcheck | null;
}

/**
 * A catalog entry as the backend sends it, which is looser than the domain
 * `McpCatalogServer` in two ways worth stating in the type: a row an older
 * backend wrote can be missing any field that version did not have yet, and the
 * lifecycle states arrive in whatever case the backend spells them.
 *
 * `toMcpCatalogServer` is what closes both gaps. Declaring this as an alias of
 * the domain model — which it was — made the mapper's `?? []` defaults invisible
 * to the compiler and let unknown fields ride into the store on a spread.
 */
export interface ApiMcpCatalogServer {
  id: string;
  name?: string;
  description?: string;
  kind?: string;
  hostId?: string | null;
  transport?: string;
  url?: string | null;
  image?: string | null;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  stdioCommand?: string | null;
  args?: string[];
  internalPort?: number | null;
  publishedPort?: number | null;
  path?: string | null;
  crossHostUrl?: string | null;
  connectionUrl?: string | null;
  headers?: ApiMcpConfigEntry[];
  environment?: ApiMcpConfigEntry[];
  volumes?: ApiMcpNamedVolume[];
  healthcheck?: ApiMcpHealthcheck | null;
  supportServices?: ApiMcpSupportService[];
  desiredState?: string;
  runtimeState?: string;
  operationState?: string;
  operationError?: string | null;
  checkStatus?: string;
  checkError?: string | null;
  checkedAt?: number | null;
  latencyMs?: number | null;
  revision?: number;
  appliedRevision?: number;
  pendingChanges?: boolean;
  serviceKey?: string | null;
  createdAt?: number;
  updatedAt?: number;
}

/** A named volume a delete deliberately left behind. */
export interface ApiMcpRetainedResource {
  id: string;
  serverId?: string | null;
  serverName?: string;
  hostId?: string;
  type?: string;
  name?: string;
  createdAt?: number;
}

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
  /** null on a blueprint written before the library had categories */
  category: string | null;
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

/**
 * One scheduled job, as hermes records it. `schedule` is hermes' own rendering —
 * only a cron schedule has an expression, while `once` and `interval` jobs are
 * stored as a timestamp and a minute count, so the display string is the one form
 * present for every kind and the one the editor hands back.
 */
export interface ApiCronJob {
  id: string;
  name: string | null;
  prompt: string | null;
  schedule: string | null;
  scheduleKind: string | null;
  deliver: string | null;
  enabled: boolean;
  state: string | null;
  repeatTimes: number | null;
  repeatDone: number;
  createdAt: number | null;
  nextRunAt: number | null;
  lastRunAt: number | null;
  lastStatus: string | null;
  lastError: string | null;
  skills: string[];
}

/** A profile's schedule, and whether the gateway that fires it is up. */
export interface ApiCronJobs {
  jobs: ApiCronJob[];
  schedulerRunning: boolean;
}

/** Create or edit a job. Every field is optional on an edit; a blank one is left alone. */
export interface ApiCronJobRequest {
  schedule?: string;
  prompt?: string;
  name?: string;
  deliver?: string;
  repeat?: number | null;
  skills?: string[];
}

/** One webhook route. The HMAC secret is never part of a listing. */
export interface ApiWebhookSubscription {
  name: string;
  description: string | null;
  url: string;
  events: string[];
  prompt: string | null;
  skills: string[];
  deliver: string | null;
  deliverOnly: boolean;
  secretMasked: string;
  createdAt: number | null;
}

/** The listener a route arrives on. */
export interface ApiWebhookPlatform {
  enabled: boolean;
  host: string | null;
  port: number | null;
  published: boolean;
}

export interface ApiWebhooks {
  subscriptions: ApiWebhookSubscription[];
  platform: ApiWebhookPlatform;
}

export interface ApiSubscribeWebhookRequest {
  name: string;
  prompt?: string;
  description?: string;
  events?: string[];
  skills?: string[];
  deliver?: string;
  deliverChatId?: string;
  deliverOnly?: boolean;
}
