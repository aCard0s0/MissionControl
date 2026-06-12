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

export type McpStatus = 'connected' | 'error' | 'disabled';

export interface McpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse';
  status: McpStatus;
  tools: number;
  latencyMs: number | null;
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
