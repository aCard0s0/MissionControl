// Domain models mirroring real Hermes Agent structures.
// A "container" is a Docker deployment of Hermes; an "agent" is a Hermes
// profile (~/.hermes/profiles/<name>/) living inside one container.

export type ContainerStatus = 'running' | 'stopped' | 'unhealthy' | 'unknown';
export type AgentState = 'active' | 'idle' | 'dormant';
export type LogLevel = 'info' | 'warn' | 'error' | 'debug';

export type DockerHostStatus = 'connected' | 'connecting' | 'error' | 'disconnected';

/** A Docker daemon Mission Control can deploy Hermes containers to. */
export interface DockerHost {
  id: string;
  name: string;
  /** unix:///var/run/docker.sock for local, tcp://host:port for remote */
  url: string;
  kind: 'local' | 'remote';
  status: DockerHostStatus;
  engine: string | null;       // e.g. "Docker 27.3"
  apiVersion: string | null;   // e.g. "1.47"
  latencyMs: number | null;
  /** human-readable reason when the host is not connected */
  note: string | null;
}

export type ModelProviderStatus = 'connected' | 'error' | 'unknown';

/** A self-hosted model server (ollama) Mission Control can pull models on. */
export interface ModelProvider {
  id: string;
  name: string;
  /** base url of the ollama server, e.g. http://host.docker.internal:11434 */
  url: string;
  kind: 'ollama';
  status: ModelProviderStatus;
  version: string | null;      // e.g. "0.6.4"
  /** human-readable reason when the provider is not connected */
  detail: string | null;
}

/** A model available on an ollama provider (from GET {url}/api/tags). */
export interface OllamaModel {
  name: string;                // e.g. "gemma3:4b"
  sizeBytes: number;
  family: string;              // e.g. "gemma3"
  parameterSize: string;       // e.g. "4.3B"
  modifiedAt: number;          // epoch ms
}

/** One tag of the Hermes image as seen from a specific docker host. */
export interface ImageTag {
  tag: string;
  pulled: boolean;             // already in that host's image store
}

/** Registry and local tags for one docker host, as the backend merged them. */
export interface ImageCatalog {
  repository: string;
  tags: ImageTag[];            // newest first
  registryStatus: string;      // ok | cached | unavailable | unsupported | disabled
  fetchedAt: number;
}

export interface HermesContainer {
  id: string;
  name: string;
  shortId: string;
  hostId: string;              // DockerHost this container runs on
  status: ContainerStatus;
  image: string;
  version: string;
  startedAt: number | null;       // epoch ms, null when stopped
  cpu: number;                    // percent 0–100
  ram: number;                    // MB used
  ramTotal: number;               // MB
  disk: number;                   // GB used
  diskTotal: number;              // GB
  netIn: number;                  // KB/s
  netOut: number;                 // KB/s
  cpuHist: number[];
  ramHist: number[];
  netHist: number[];
}

export interface SkillRef {
  id: string;
  name: string;
  source: 'bundled' | 'user' | 'hub';
  version: string;
  description: string;
  enabled: boolean;
}

/** Full SKILL.md body + file listing for inspecting/editing a skill. */
export interface SkillContent {
  name: string;
  path: string;
  body: string;
  files: string[];
}

export type McpStatus = 'unknown' | 'checking' | 'connected' | 'error' | 'disabled';

export interface McpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse';
  enabled: boolean;
  origin: 'custom' | 'catalog';
  catalogServerId: string | null;
  syncedRevision: number | null;
  catalogRevision: number | null;
  updateAvailable: boolean;
  status: McpStatus;
  tools: number;
  latencyMs: number | null;
  error?: string | null;
  checkedAt?: number | null;
  url?: string;                   // http/sse endpoint (for the edit form)
  command?: string;               // stdio command
  args?: string;                  // stdio args, space-joined
}

// ── global MCP server catalog ──────────────────────────────────────────────

export type McpCatalogKind = 'managed' | 'external' | 'stdio';
export type McpTransport = 'stdio' | 'http' | 'sse';
export type McpDesiredState = 'running' | 'stopped';
export type McpRuntimeState = 'running' | 'stopped' | 'missing' | 'unknown' | 'error';
export type McpCheckStatus = 'unknown' | 'checking' | 'connected' | 'error';

/** A redacted environment variable or HTTP header. The backend never returns
 *  `value` for a secret; blank values in update requests retain a stored value. */
export interface McpConfigEntry {
  key: string;
  value?: string | null;
  secret: boolean;
  set?: boolean;
  recoverable?: boolean;
  clear?: boolean;
}

/** Named volumes only. Host paths and bind mounts are intentionally not
 *  representable by the UI or API model. */
export interface McpNamedVolume {
  name: string;
  target: string;
}

export interface McpHealthcheck {
  test: string[];
  interval?: string | null;
  timeout?: string | null;
  retries?: number | null;
  startPeriod?: string | null;
}

/** Support services are rendered by the backend into the managed stack. The
 *  first UI version preserves this structured data when editing a server. */
export interface McpSupportService {
  name: string;
  image: string;
  platform?: string | null;
  entrypoint?: string[];
  command?: string[];
  environment?: McpConfigEntry[];
  volumes?: McpNamedVolume[];
  healthcheck?: McpHealthcheck | null;
}

export interface McpCatalogServer {
  id: string;
  name: string;
  description: string;
  kind: McpCatalogKind;
  hostId: string | null;
  transport: McpTransport;
  url: string | null;
  image: string | null;
  platform: string | null;
  entrypoint: string[];
  /** List-form command override for managed Compose services. */
  command: string[];
  stdioCommand: string | null;
  args: string[];
  internalPort: number | null;
  publishedPort: number | null;
  path: string | null;
  crossHostUrl: string | null;
  /** Computed usable endpoint returned by the backend. */
  connectionUrl: string | null;
  headers: McpConfigEntry[];
  environment: McpConfigEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpSupportService[];
  desiredState: McpDesiredState;
  runtimeState: McpRuntimeState;
  operationState: string;
  operationError: string | null;
  checkStatus: McpCheckStatus;
  checkError: string | null;
  checkedAt: number | null;
  latencyMs: number | null;
  revision: number;
  appliedRevision: number;
  pendingChanges: boolean;
  serviceKey: string | null;
  createdAt: number;
  updatedAt: number;
}

/** Editable catalog fields accepted by POST and PUT. Host assignment is
 *  immutable after create; the backend enforces that invariant. */
export interface McpCatalogServerInput {
  name: string;
  description: string;
  kind: McpCatalogKind;
  hostId: string | null;
  transport: McpTransport;
  url: string | null;
  image: string | null;
  platform: string | null;
  entrypoint: string[];
  command: string[];
  stdioCommand: string | null;
  args: string[];
  internalPort: number | null;
  publishedPort: number | null;
  path: string | null;
  crossHostUrl: string | null;
  headers: McpConfigEntry[];
  environment: McpConfigEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpSupportService[];
}

export interface McpRetainedResource {
  id: string;
  serverId: string | null;
  serverName: string;
  hostId: string;
  type: 'volume' | string;
  name: string;
  createdAt: number;
}

export type IntegrationKind =
  | 'slack' | 'whatsapp' | 'discord' | 'telegram' | 'signal' | 'email'
  | 'github' | 'filesystem' | 'browser' | 'database';

export type IntegrationStatus = 'up' | 'degraded' | 'down' | 'off';

export interface Integration {
  kind: IntegrationKind;
  status: IntegrationStatus;
  detail: string;                 // e.g. "gateway up 14d · @ops-bot"
}

export interface SessionInfo {
  id: string;
  title: string;
  platform: string;
  startedAt: number;
  messages: number;
  status: 'open' | 'closed';
}

/** One turn in a session's chat history (from the agent's state.db messages table). */
export interface ChatMessage {
  role: string;                   // user | assistant | tool | system
  content: string;
  toolName?: string | null;       // tool name for tool turns / tool results
  toolCalls?: string | null;      // raw JSON string of requested tool calls
  reasoning?: string | null;      // model reasoning content, when present
  ts: number;                     // epoch ms
}

export interface AgentProfile {
  id: string;
  containerId: string;
  name: string;                   // profile name → ~/.hermes/profiles/<name>/
  role: string;                   // human description
  state: AgentState;
  provider: string;
  model: string;
  apiKeyMasked: string;
  cwd: string;                    // terminal.cwd from config.yaml
  soul: string;                   // SOUL.md content (the one safe-editable file)
  memoryMd: string;               // MEMORY.md (read-only view)
  configYaml: string;             // config.yaml (read-only view)
  skills: SkillRef[];
  mcp: McpServer[];
  integrations: Integration[];
  sessions: SessionInfo[];
  msgsToday: number;
  tokensToday: number;            // thousands
  errorRate: number;              // percent
  lastActive: number;             // epoch ms
}

/** An MCP server defined in a profile template (no live status — that exists only
 *  once deployed onto an agent). */
export interface TemplateMcp {
  name: string;
  transport: 'stdio' | 'http' | 'sse';
  url?: string;
  command?: string;
  args?: string;
  enabled: boolean;
}

/** A template secret as returned from the backend — only flags, never any value.
 *  `recoverable` is false when a stored value can no longer be decrypted (the
 *  MC_SECRET_KEY changed) and must be re-entered. */
export interface TemplateSecret {
  key: string;
  set: boolean;                   // a value is stored
  recoverable: boolean;           // the stored value still decrypts
}

/**
 * A reusable agent blueprint (soul, memory, skills, MCP servers, encrypted keys).
 * Dashboard-owned and container-independent; applied when deploying an agent.
 * Distinct from {@link AgentProfile}, which is a live agent instance.
 */
export interface ProfileTemplate {
  id: string;
  name: string;
  description: string;
  provider: string;
  model: string;
  baseUrl: string;
  cwd: string;
  soul: string;
  memory: string;
  skills: string[];               // skill ids to install
  mcpServers: TemplateMcp[];
  secrets: TemplateSecret[];
  createdAt: number;
  updatedAt: number;
}

/** Editor payload sent to the backend on save. Blank secret values keep the
 *  stored secret; non-blank values replace it. */
export interface ProfileTemplateInput {
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
  secrets: Array<{ key: string; value: string }>;
}

export interface CronJob {
  id: string;
  containerId: string;
  agentId: string;
  name: string;
  schedule: string;               // 5-field cron / "every monday 9am" / "30m"
  prompt: string;
  deliverTo: string;              // platform the result is delivered to
  enabled: boolean;
  lastRun: number | null;
  lastStatus: 'ok' | 'fail' | null;
  nextRun: number;
}

export interface LogEntry {
  ts: number;
  level: LogLevel;
  source: string;                 // gateway / scheduler / agent name / mcp
  agentId: string | null;
  msg: string;
}

export type BoardColumn = 'queued' | 'running' | 'review' | 'done';

export interface BoardTask {
  id: string;
  containerId: string;
  agentId: string;
  title: string;
  column: BoardColumn;
  priority: 'low' | 'med' | 'high';
  tags: string[];
  createdAt: number;
}

export interface WebhookDelivery {
  ts: number;
  event: string;
  status: 'ok' | 'fail';
  code: number;
}

export interface Webhook {
  id: string;
  agentId: string;
  name: string;
  slug: string;                   // path under the gateway base url
  secretMasked: string;
  events: string[];
  active: boolean;
  deliveries: WebhookDelivery[];
}
