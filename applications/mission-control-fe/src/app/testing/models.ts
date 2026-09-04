import {
  AgentProfile, CronJob, DockerHost, Gateway, HermesContainer, McpCatalogServer, McpServer,
  ProfileTemplate, SkillRef,
} from '../core/models';

/**
 * Domain-model builders for the specs. Every one takes the identifier the tests
 * actually reason about and derives the rest, so a spec names only what its
 * subject depends on and the noise of a fully-populated model stays here.
 *
 * The defaults are deliberately unremarkable — a healthy, connected, idle
 * everything. A test that is about a failure states that failure in its patch,
 * which is then the only interesting thing on the line.
 *
 * Test-only: excluded from the app build (tsconfig.app.json) and from coverage.
 */

/** The image the fleet actually runs; update badges compare against it. */
const HERMES_IMAGE = 'nousresearch/hermes-agent';

export const container = (id: string, patch: Partial<HermesContainer> = {}): HermesContainer => ({
  id, name: id, shortId: id.slice(0, 4), hostId: 'dh-local', status: 'running',
  image: HERMES_IMAGE, version: 'v2026.7.20', imageDigest: null, release: null, startedAt: 1,
  cpu: 0, ram: 512, ramTotal: 4096, disk: 2, diskTotal: 0, netIn: 0, netOut: 0,
  cpuHist: [], ramHist: [], netHist: [], ...patch,
});

export const dockerHost = (id: string, patch: Partial<DockerHost> = {}): DockerHost => ({
  id, name: id, url: `tcp://${id}:2375`, kind: 'remote', status: 'connected',
  engine: null, apiVersion: null, latencyMs: null, note: null, ...patch,
});

export const skill = (name: string, patch: Partial<SkillRef> = {}): SkillRef => ({
  id: `s-${name}`, name, source: 'bundled', version: '1.0.0',
  description: `${name} skill`, enabled: true, ...patch,
});

/** One MCP server as a profile's own config records it. */
export const mcpServer = (name: string, patch: Partial<McpServer> = {}): McpServer => ({
  id: `m-${name}`, name, transport: 'http', enabled: true, origin: 'custom',
  catalogServerId: null, syncedRevision: null, catalogRevision: null, updateAvailable: false,
  status: 'connected', tools: 3, latencyMs: 42, error: null, checkedAt: 1,
  url: `https://${name}.example.test/mcp`, ...patch,
});

export const gateway = (patch: Partial<Gateway> = {}): Gateway => ({
  state: 'running', desiredState: 'running', activeAgents: 0, agentVersion: '0.20.5',
  sessionStore: 'ok', paused: false, pauseReason: null, ...patch,
});

export const agent = (id: string, patch: Partial<AgentProfile> = {}): AgentProfile => ({
  id, containerId: 'c-1', name: id, role: 'ops', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…key', cwd: '/opt/data',
  soul: '', memoryMd: '', configYaml: '', skills: [], mcp: [], integrations: [],
  gateway: gateway(), sessions: [],
  msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: 1, ...patch,
});

/**
 * A managed catalog entry, addressable by `id` and reachable on its own service
 * name. An external endpoint or a stdio command is the same builder with the
 * managed-only fields patched away — see {@link externalCatalogServer}.
 */
export const catalogServer = (
  id: string, patch: Partial<McpCatalogServer> = {},
): McpCatalogServer => ({
  id, name: id, description: '', repoUrl: '', kind: 'managed', hostId: 'dh-local',
  transport: 'http', url: null, image: 'mcp/image:latest', platform: null,
  entrypoint: [], command: [], stdioCommand: null, args: [], internalPort: 1100,
  publishedPort: null, path: '/mcp', crossHostUrl: null, connectionUrl: `http://${id}:1100/mcp`,
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'stopped', runtimeState: 'stopped', operationState: 'idle', operationError: null,
  checkStatus: 'unknown', checkError: null, checkedAt: null, latencyMs: null,
  revision: 1, appliedRevision: 1, pendingChanges: false, serviceKey: id,
  createdAt: 1, updatedAt: 1, ...patch,
});

/** An endpoint Mission Control only points at: no host, image, port or service. */
export const externalCatalogServer = (
  id: string, patch: Partial<McpCatalogServer> = {},
): McpCatalogServer => catalogServer(id, {
  kind: 'external', hostId: null, url: `https://${id}.example.test/mcp`, image: null,
  internalPort: null, path: null, connectionUrl: null, serviceKey: null,
  runtimeState: 'unknown', ...patch,
});

export const template = (id: string, patch: Partial<ProfileTemplate> = {}): ProfileTemplate => ({
  id, name: id, icon: '', description: '', category: 'general', provider: 'anthropic', model: 'claude-fable-5',
  baseUrl: '', cwd: '/opt/data', soul: '', memory: '', skills: [], mcpServers: [],
  secrets: [], createdAt: 1, updatedAt: 1, ...patch,
});

export const cronJob = (id: string, patch: Partial<CronJob> = {}): CronJob => ({
  id, containerId: 'c-1', agentId: 'a-1', name: `job ${id}`, schedule: '0 9 * * *',
  prompt: 'do the thing', deliverTo: 'slack', enabled: true,
  nextRun: 5_000, lastRun: null, lastStatus: 'ok', ...patch,
});
