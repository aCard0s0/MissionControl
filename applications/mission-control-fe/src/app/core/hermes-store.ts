import { Injectable, computed, signal } from '@angular/core';
import {
  AgentProfile, BoardColumn, BoardTask, ChatMessage, ContainerStatus, CronJob, DockerHost,
  HermesContainer, ImageCatalog, Integration, LogEntry, McpCatalogServer, McpCatalogServerInput,
  McpRetainedResource, McpServer, ModelProvider, OllamaModel, ProfileTemplate,
  ProfileTemplateInput, SessionInfo, SkillContent, SkillRef, Webhook,
} from './models';
import {
  buildMockChat, seedAgents, seedContainers, seedDockerHosts, seedImageTags, seedJobs, seedLogs,
  seedMcpCatalogServers, seedSkillBodies, seedTasks, seedTemplates, seedWebhooks,
} from './mock-data';
import { runtimeConfig } from './app-config';
import {
  ApiAgentProfile, ApiAgentSetup, ApiAuxiliaryModel, ApiImageTags, ApiMcpCatalogServer,
  ApiModelProvider, ApiProfileTemplate, ApiPullState, ApiSetupAuthProvider, HermesApi,
} from './hermes-api';
import { maskTail } from '../shared/secret';

let uid = 0;
const nid = (p: string) => `${p}-${Date.now().toString(36)}-${uid++}`;

/** Bootstrap mirror of the backend model-provider registry
 *  (ModelProviderRegistry.java) — the picker uses this until the live
 *  `GET /api/providers` resolves, and as the sole source in mock mode. Keep in
 *  sync with the Java registry; the backend is authoritative when reachable. */
const DEFAULT_LLM_PROVIDERS: ApiModelProvider[] = [
  { key: 'nous', label: 'Nous (account)', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'openrouter', label: 'OpenRouter', needsKey: true, oauth: false, hasCatalog: true, envVar: 'OPENROUTER_API_KEY' },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true, envVar: 'ANTHROPIC_API_KEY' },
  { key: 'openai', label: 'OpenAI', needsKey: true, oauth: false, hasCatalog: true, envVar: 'OPENAI_API_KEY' },
  { key: 'gemini', label: 'Google AI Studio', needsKey: true, oauth: false, hasCatalog: false, envVar: 'GOOGLE_API_KEY' },
  { key: 'xai', label: 'xAI / Grok', needsKey: true, oauth: false, hasCatalog: false, envVar: 'XAI_API_KEY' },
  { key: 'deepseek', label: 'DeepSeek', needsKey: true, oauth: false, hasCatalog: false, envVar: 'DEEPSEEK_API_KEY' },
  { key: 'nvidia', label: 'NVIDIA NIM', needsKey: true, oauth: false, hasCatalog: false, envVar: 'NVIDIA_API_KEY' },
  { key: 'zai', label: 'Z.AI / GLM', needsKey: true, oauth: false, hasCatalog: false, envVar: 'GLM_API_KEY' },
  { key: 'kimi-coding', label: 'Kimi / Moonshot', needsKey: true, oauth: false, hasCatalog: false, envVar: 'KIMI_API_KEY' },
  { key: 'minimax', label: 'MiniMax', needsKey: true, oauth: false, hasCatalog: false, envVar: 'MINIMAX_API_KEY' },
  { key: 'stepfun', label: 'StepFun', needsKey: true, oauth: false, hasCatalog: false, envVar: 'STEPFUN_API_KEY' },
];

/** Offline model lists mirroring the server's `mc.models` catalog defaults
 *  (application.yml) — the only model source in mock mode and the fallback when
 *  the backend (or provider API) is unreachable. Keep in sync with `MC_MODELS_*`. */

const FALLBACK_MODELS: Record<string, string[]> = {
  nous: ['Hermes-4-405B', 'Hermes-4-70B', 'Hermes-4-14B'],
  openrouter: ['nousresearch/hermes-4-405b', 'anthropic/claude-opus-4.7', 'anthropic/claude-sonnet-4', 'openai/gpt-5.2', 'google/gemini-2.5-pro', 'deepseek/deepseek-chat'],
  anthropic: ['claude-fable-5', 'claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5-20251001'],
  openai: ['gpt-5.2', 'gpt-5.2-mini', 'gpt-5.1', 'gpt-4.1'],
};

/** [label, envVar] pairs surfaced in the mock setup tab. */
const MOCK_SETUP_API_KEYS: Array<[string, string]> = [
  ['OpenRouter', 'OPENROUTER_API_KEY'],
  ['OpenAI', 'OPENAI_API_KEY'],
  ['Anthropic', 'ANTHROPIC_API_KEY'],
  ['Tavily', 'TAVILY_API_KEY'],
  ['GitHub', 'GITHUB_TOKEN'],
];

/** [label, tokenVar, homeVar] triples for the mock messaging section. */
const MOCK_SETUP_MESSAGING: Array<[string, string, string | null]> = [
  ['Telegram', 'TELEGRAM_BOT_TOKEN', 'TELEGRAM_HOME_CHANNEL'],
  ['Slack', 'SLACK_BOT_TOKEN', 'SLACK_HOME_CHANNEL'],
  ['WhatsApp', 'WHATSAPP_TOKEN', null],
  ['Email', 'EMAIL_PASSWORD', null],
];

/**
 * A request to open the bottom terminal panel. Everything past `seq` is
 * optional: an empty request just opens the panel, while a targeted one (from
 * the agent shortcut) pins the tab to a container and runs a command in it.
 */
export interface TerminalRequest {
  /** monotonic — the panel acts once per new value, even for an identical target */
  seq: number;
  hostId?: string;
  containerId?: string;
  /** tab label; the profile name for an agent shortcut */
  label?: string;
  /** AgentProfile.id — lets a repeat click focus the tab it already opened */
  agentKey?: string;
  /** typed into the shell once it is live */
  command?: string;
}

/**
 * Hermes data store. In `mock` data mode (the dev default, see
 * public/config.js) it seeds demo data and simulates telemetry; in `live`
 * mode it starts empty and is meant to be fed by a backend adapter hitting
 * `apiBaseUrl`. The UI only consumes the signals/actions surface, so the
 * adapter swap never touches components. All pages read through
 * `selectedContainer`, which enforces the "never mix containers" rule at
 * the store level.
 */
@Injectable({ providedIn: 'root' })
export class HermesStore {
  readonly config = runtimeConfig();
  private readonly mock = this.config.dataMode === 'mock';
  private readonly api = new HermesApi(this.config.apiBaseUrl);

  /** Health of the Mission Control backend API (live mode only). */
  readonly backendStatus = signal<'mock' | 'connecting' | 'connected' | 'unreachable'>(
    this.mock ? 'mock' : 'connecting');

  /** Transient error toast for failed live actions. */
  readonly liveError = signal<string | null>(null);

  /** Set by pages that want the bottom terminal panel opened. Null until the
   *  first request; `seq` is what makes a repeat request with an identical
   *  target still register as a new one. */
  readonly terminalRequest = signal<TerminalRequest | null>(null);
  private termSeq = 0;

  /**
   * Open the bottom terminal panel. With no target it behaves as it always
   * has — the panel seeds a tab on the globally selected container. With one,
   * the panel opens (or focuses) a tab bound to that container and types
   * `command` into it once the shell is live.
   */
  openTerminal(target?: Omit<TerminalRequest, 'seq'>): void {
    this.terminalRequest.set({ ...target, seq: ++this.termSeq });
  }

  readonly dockerHosts = signal<DockerHost[]>(
    this.mock
      ? seedDockerHosts(this.config.dockerSocket)
      : [{
          id: 'dh-local', name: 'localhost', url: this.config.dockerSocket, kind: 'local',
          status: 'disconnected', engine: null, apiVersion: null, latencyMs: null,
          note: 'waiting for backend connection',
        }]);
  readonly modelProviders = signal<ModelProvider[]>(
    this.mock
      ? [{
          id: 'mp-local', name: 'local ollama', url: 'http://host.docker.internal:11434',
          kind: 'ollama', status: 'connected', version: '0.6.x', detail: null,
        }]
      : []);
  /** LLM provider registry for the create-agent / template pickers. Seeded with
   *  the bootstrap mirror, refreshed from the backend in live mode. */
  readonly llmProviders = signal<ApiModelProvider[]>(DEFAULT_LLM_PROVIDERS);
  readonly containers = signal<HermesContainer[]>(this.mock ? seedContainers() : []);
  readonly agents = signal<AgentProfile[]>(this.mock ? seedAgents() : []);
  readonly jobs = signal<CronJob[]>(this.mock ? seedJobs() : []);
  readonly tasks = signal<BoardTask[]>(this.mock ? seedTasks() : []);
  readonly webhooks = signal<Webhook[]>(this.mock ? seedWebhooks() : []);
  /** Reusable agent blueprints — global, not scoped to a container. */
  readonly profileTemplates = signal<ProfileTemplate[]>(this.mock ? seedTemplates() : []);
  /** Reusable global MCP definitions, including managed Compose services. */
  readonly mcpServers = signal<McpCatalogServer[]>(this.mock ? seedMcpCatalogServers() : []);
  readonly mcpServersLoading = signal(false);
  readonly retainedMcpResources = signal<McpRetainedResource[]>([]);
  private readonly logsByContainer = signal<Record<string, LogEntry[]>>(this.mock ? seedLogs() : {});
  readonly logsLoading = signal(false);
  readonly logsUpdatedAt = signal<number | null>(null);
  readonly logsError = signal<string | null>(null);

  readonly selectedContainerId = signal<string>(this.mock ? 'c-prod' : '');

  // ── derived, all scoped to the active container ────────────────────────
  readonly selectedContainer = computed(() =>
    this.containers().find(c => c.id === this.selectedContainerId()) ?? null);

  readonly containerAgents = computed(() =>
    this.agents().filter(a => a.containerId === this.selectedContainerId()));

  readonly containerJobs = computed(() =>
    this.jobs().filter(j => j.containerId === this.selectedContainerId()));

  readonly containerTasks = computed(() =>
    this.tasks().filter(t => t.containerId === this.selectedContainerId()));

  readonly containerLogs = computed(() =>
    (this.logsByContainer()[this.selectedContainerId()] ?? []).slice().sort((a, b) => b.ts - a.ts));

  readonly containerWebhooks = computed(() => {
    const ids = new Set(this.containerAgents().map(a => a.id));
    return this.webhooks().filter(w => ids.has(w.agentId));
  });

  readonly fleetHealth = computed<ContainerStatus>(() => {
    const cs = this.containers();
    if (cs.some(c => c.status === 'unhealthy')) return 'unhealthy';
    if (cs.some(c => c.status === 'running')) return 'running';
    return 'stopped';
  });

  /** Worst-of summary across docker hosts, for the sidebar chip. */
  readonly dockerOverall = computed(() => {
    const hs = this.dockerHosts();
    if (hs.some(h => h.status === 'error')) return 'error';
    if (hs.some(h => h.status === 'connecting')) return 'connecting';
    if (hs.some(h => h.status === 'connected')) return 'connected';
    return 'disconnected';
  });

  hostById = (id: string) => this.dockerHosts().find(h => h.id === id) ?? null;

  /** Banner text shown app-wide while live mode has no working backend. */
  readonly liveNotice = computed(() => {
    switch (this.backendStatus()) {
      case 'mock':
      case 'connected': return null;
      case 'connecting': return 'live mode — connecting to backend…';
      case 'unreachable':
        return this.config.apiBaseUrl
          ? `live mode — backend unreachable at ${this.config.apiBaseUrl}, retrying…`
          : 'live mode — backend unreachable (is mission-control-server running?), retrying…';
    }
  });

  constructor() {
    if (this.mock) {
      setInterval(() => this.tick(), 1500);
    } else {
      this.probeBackend();
    }
  }

  agentById = (id: string | null) => this.agents().find(a => a.id === id) ?? null;

  templateById = (id: string | null) => this.profileTemplates().find(t => t.id === id) ?? null;

  mcpServerById = (id: string | null) => this.mcpServers().find(s => s.id === id) ?? null;

  private toAgentProfile(api: ApiAgentProfile): AgentProfile {
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
        source: s.source as any,
        version: s.version,
        description: s.description,
        enabled: !!s.enabled,
      })),
      mcp: (api.mcp ?? []).map(m => ({
        id: m.id,
        name: m.name,
        transport: m.transport as any,
        enabled: m.enabled !== false,
        origin: m.origin === 'catalog' ? 'catalog' : 'custom',
        catalogServerId: m.catalogServerId ?? null,
        syncedRevision: m.syncedRevision ?? null,
        catalogRevision: m.catalogRevision ?? null,
        updateAvailable: !!m.updateAvailable,
        status: m.status as any,
        tools: m.tools,
        latencyMs: m.latencyMs,
        error: m.error ?? null,
        checkedAt: m.checkedAt ?? null,
        url: m.url ?? undefined,
        command: m.command ?? undefined,
        args: m.args ?? undefined,
      })),
      integrations: (api.integrations ?? []).map(i => ({
        kind: i.kind as any,
        status: i.status as any,
        detail: i.detail,
      })),
      sessions: [],
      msgsToday: 0,
      tokensToday: 0,
      errorRate: 0,
      lastActive: api.lastActive,
    };
  }

  // ── live mode: backend adapter ─────────────────────────────────────────
  private livePollersStarted = false;
  private netMeta = new Map<string, { rx: number; tx: number; at: number }>();

  private async probeBackend(): Promise<void> {
    try {
      await this.api.health();
      this.backendStatus.set('connected');
      this.initLive();
    } catch {
      this.backendStatus.set('unreachable');
      setTimeout(() => this.probeBackend(), 10_000);
    }
  }

  private async initLive(): Promise<void> {
    if (this.livePollersStarted) return;
    this.livePollersStarted = true;
    await Promise.all([
      this.refreshHosts(), this.refreshModelProviders(), this.refreshProviderRegistry(),
      this.refreshContainers(), this.refreshBoard(), this.refreshTemplates(),
      this.refreshMcpServers(), this.refreshRetainedMcpResources(),
    ]);
    await this.refreshAgents();   // needs the container list
    void this.refreshImageCatalogs();
    setInterval(() => this.refreshContainers(), 10_000);
    setInterval(() => this.refreshAgents(), 12_000);
    // published tags change on the order of days, and each lookup probes the
    // daemon — deliberately far slower than the container poll
    setInterval(() => this.refreshImageCatalogs(), 300_000);
    setInterval(() => this.pollStats(), 3_000);
    setInterval(() => this.pollLogs(), 5_000);
    this.pollStats();
    this.pollLogs();
  }

  toast(message: string): void {
    this.liveError.set(message);
    setTimeout(() => this.liveError.set(null), 6_000);
  }

  /** Run `fn` over `items` with at most `limit` in flight at once. Caps the
   *  per-container fan-out of the pollers so a slow daemon can't open dozens of
   *  concurrent requests every tick. */
  private async mapPool<T, R>(items: readonly T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
    const results: R[] = new Array(items.length);
    let next = 0;
    const worker = async () => {
      while (next < items.length) {
        const idx = next++;
        results[idx] = await fn(items[idx]);
      }
    };
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
    return results;
  }

  private async refreshHosts(): Promise<void> {
    try {
      this.dockerHosts.set(await this.api.hosts());
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  private async refreshContainers(): Promise<void> {
    try {
      const list = await this.api.containers();
      this.containers.update(prev => {
        const prevById = new Map(prev.map(c => [c.id, c]));
        return list.map(c => {
          const old = prevById.get(c.id);
          return {
            id: c.id, name: c.name, shortId: c.shortId, hostId: c.hostId,
            status: c.status, image: c.image, version: c.version, startedAt: c.startedAt,
            disk: c.sizeRootFsGb ?? 0, diskTotal: 0,   // daemons report size, not quota
            cpu: old?.cpu ?? 0, ram: old?.ram ?? 0, ramTotal: old?.ramTotal ?? 0,
            netIn: old?.netIn ?? 0, netOut: old?.netOut ?? 0,
            cpuHist: old?.cpuHist ?? [], ramHist: old?.ramHist ?? [], netHist: old?.netHist ?? [],
          };
        });
      });
      // the selected id can also go stale — an updated container is recreated
      // under a new id, and out-of-band removals happen too. Never clear on a
      // transient empty inventory.
      if (list.length && !list.some(c => c.id === this.selectedContainerId())) {
        this.selectedContainerId.set(list[0].id);
      }
    } catch { /* keep last inventory */ }
  }

  private agentsInFlight = false;
  private async refreshAgents(): Promise<void> {
    if (this.agentsInFlight) return;   // skip a tick rather than overlap fan-outs
    this.agentsInFlight = true;
    try {
      const containers = this.containers();
      if (!containers.length) {
        this.agents.set([]);
        return;
      }
      const prev = this.agents();
      const lists = await this.mapPool(containers, 6, c => {
        if (c.status === 'stopped') return Promise.resolve(prev.filter(a => a.containerId === c.id));
        return this.api.agents(c.hostId, c.id)
          .then(list => list.map(a => {
            const fresh = this.toAgentProfile(a);
            const old = prev.find(p => p.id === fresh.id);
            if (!old) return fresh;
            return {
              ...fresh,
              mcp: fresh.mcp.map(server => {
                const prior = old.mcp.find(m => m.name === server.name);
                return prior?.status === 'checking' && server.status === 'unknown'
                  ? { ...server, status: 'checking' as const }
                  : server;
              }),
            };
          }))
          // transient per-container failure — keep its last known profiles
          .catch(() => prev.filter(a => a.containerId === c.id));
      });
      this.agents.set(lists.flat());
    } finally {
      this.agentsInFlight = false;
    }
  }

  private statsInFlight = false;
  private async pollStats(): Promise<void> {
    if (this.statsInFlight) return;   // skip a tick rather than overlap fan-outs
    this.statsInFlight = true;
    try {
      const running = this.containers().filter(c => c.status === 'running' || c.status === 'unhealthy');
      await this.mapPool(running, 6, async c => {
        try {
          const s = await this.api.stats(c.hostId, c.id);
          const prev = this.netMeta.get(c.id);
          this.netMeta.set(c.id, { rx: s.rxBytes, tx: s.txBytes, at: s.sampledAt });
          const dt = prev ? (s.sampledAt - prev.at) / 1000 : 0;
          const netIn = prev && dt > 0 ? Math.max(0, (s.rxBytes - prev.rx) / dt / 1024) : 0;
          const netOut = prev && dt > 0 ? Math.max(0, (s.txBytes - prev.tx) / dt / 1024) : 0;
          const push = (h: number[], v: number) => [...h.slice(-59), v];
          this.containers.update(cs => cs.map(x => x.id !== c.id ? x : {
            ...x, cpu: s.cpuPercent, ram: s.ramMb, ramTotal: s.ramTotalMb, netIn, netOut,
            cpuHist: push(x.cpuHist, s.cpuPercent),
            ramHist: push(x.ramHist, s.ramMb),
            netHist: push(x.netHist, netIn + netOut),
          }));
        } catch { /* container may have stopped between polls */ }
      });
    } finally {
      this.statsInFlight = false;
    }
  }

  private readonly logsInFlight = new Set<string>();
  private async pollLogs(): Promise<void> {
    const c = this.selectedContainer();
    if (!c || c.status === 'stopped' || this.logsInFlight.has(c.id)) return;
    this.logsInFlight.add(c.id);
    if (this.selectedContainerId() === c.id) {
      this.logsLoading.set(true);
      this.logsError.set(null);
    }
    try {
      const lines = await this.api.logs(c.hostId, c.id, 100);
      this.logsByContainer.update(m => ({
        ...m,
        [c.id]: lines.map(l => ({ ...l, agentId: null })),
      }));
      if (this.selectedContainerId() === c.id) this.logsUpdatedAt.set(Date.now());
    } catch (e: any) {
      if (this.selectedContainerId() === c.id) this.logsError.set(e?.message ?? 'log refresh failed');
    } finally {
      this.logsInFlight.delete(c.id);
      if (this.selectedContainerId() === c.id) this.logsLoading.set(false);
    }
  }

  refreshLogs(): void {
    void this.pollLogs();
  }

  private async refreshBoard(): Promise<void> {
    try {
      const tasks = await this.api.boardTasks();
      this.tasks.set(tasks.map(t => ({ ...t, agentId: t.agentId ?? '', tags: t.tags ?? [] })));
    } catch { /* board is non-critical */ }
  }

  // ── telemetry simulation ───────────────────────────────────────────────
  private tick(): void {
    this.containers.update(list => list.map(c => {
      if (c.status === 'stopped') return c;
      const drift = (v: number, j: number, min: number, max: number) =>
        Math.min(max, Math.max(min, v + (Math.random() - 0.5) * j));
      const cpu = c.status === 'unhealthy' ? drift(c.cpu, 9, 62, 99) : drift(c.cpu, 7, 4, 70);
      const ram = drift(c.ram, 40, c.ramTotal * 0.2, c.ramTotal * (c.status === 'unhealthy' ? 0.97 : 0.7));
      const netIn = Math.max(0, drift(c.netIn, 25, 0, 400));
      const netOut = Math.max(0, drift(c.netOut, 12, 0, 200));
      const push = (h: number[], v: number) => [...h.slice(-59), v];
      return {
        ...c, cpu, ram, netIn, netOut,
        cpuHist: push(c.cpuHist, cpu),
        ramHist: push(c.ramHist, ram),
        netHist: push(c.netHist, netIn + netOut),
      };
    }));

    // occasionally emit a log line on running containers
    if (Math.random() < 0.3) {
      const running = this.containers().filter(c => c.status !== 'stopped');
      if (running.length) {
        const c = running[Math.floor(Math.random() * running.length)];
        const pool: Array<[LogEntry['level'], string, string]> = c.status === 'unhealthy'
          ? [
              ['warn', 'system', 'memory pressure: page cache reclaim'],
              ['error', 'agent', 'probe timeout after 5000ms'],
              ['warn', 'system', `cpu ${Math.round(c.cpu)}% sustained`],
            ]
          : [
              ['info', 'gateway', `event ack in ${Math.round(40 + Math.random() * 120)}ms`],
              ['debug', 'agent', 'context window compacted'],
              ['info', 'scheduler', 'cron heartbeat ok'],
              ['debug', 'mcp', 'tool registry refreshed'],
            ];
        const [level, source, msg] = pool[Math.floor(Math.random() * pool.length)];
        const agents = this.agents().filter(a => a.containerId === c.id);
        const agent = agents.length && Math.random() < 0.7 ? agents[Math.floor(Math.random() * agents.length)] : null;
        this.appendLog(c.id, { ts: Date.now(), level, source, agentId: agent?.id ?? null, msg });
      }
    }
  }

  private appendLog(containerId: string, entry: LogEntry): void {
    this.logsByContainer.update(m => ({
      ...m,
      [containerId]: [...(m[containerId] ?? []).slice(-199), entry],
    }));
  }

  // ── docker host actions ────────────────────────────────────────────────
  addDockerHost(name: string, url: string): void {
    if (!this.mock) {
      this.api.addHost(name, url)
        .then(() => this.refreshHosts())
        .catch(e => this.toast(`add host failed: ${e.message}`));
      return;
    }
    const host: DockerHost = {
      id: nid('dh'), name, url, kind: 'remote',
      status: 'connecting', engine: null, apiVersion: null, latencyMs: null, note: null,
    };
    this.dockerHosts.update(hs => [...hs, host]);
    this.probeHost(host.id);
  }

  removeDockerHost(id: string): void {
    const host = this.hostById(id);
    if (!host || host.kind === 'local') return;   // local socket is not removable
    if (!this.mock) {
      this.api.deleteHost(id)
        .then(() => this.refreshHosts())
        .catch(e => this.toast(`remove host failed: ${e.message}`));
      return;
    }
    this.dockerHosts.update(hs => hs.filter(h => h.id !== id));
  }

  checkDockerHost(id: string): void {
    this.dockerHosts.update(hs => hs.map(h => h.id === id ? { ...h, status: 'connecting' as const } : h));
    if (!this.mock) {
      this.api.checkHost(id)
        .then(host => this.dockerHosts.update(hs => hs.map(h => h.id === id ? host : h)))
        .catch(e => {
          this.toast(`host check failed: ${e.message}`);
          this.refreshHosts();
        });
      return;
    }
    this.probeHost(id);
  }

  /** Simulated daemon ping — mock mode only; live mode asks the backend. */
  private probeHost(id: string): void {
    setTimeout(() => {
      this.dockerHosts.update(hs => hs.map(h => {
        if (h.id !== id) return h;
        const ok = h.kind === 'local' || Math.random() > 0.15;
        return ok
          ? { ...h, status: 'connected' as const, engine: 'Docker 27.3', apiVersion: '1.47',
              latencyMs: h.kind === 'local' ? 2 : 18 + Math.floor(Math.random() * 90), note: null }
          : { ...h, status: 'error' as const, engine: null, apiVersion: null, latencyMs: null,
              note: 'connection refused — check the daemon address and TLS setup' };
      }));
    }, 800);
  }

  // ── global MCP server catalog ──────────────────────────────────────────

  /** Keeps tolerance for additive backend fields and older rows in one place. */
  private toMcpCatalogServer(api: ApiMcpCatalogServer): McpCatalogServer {
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

  private upsertMcpCatalogServer(server: McpCatalogServer): void {
    this.mcpServers.update(servers => [
      server,
      ...servers.filter(item => item.id !== server.id),
    ]);
  }

  async refreshMcpServers(silent = false): Promise<void> {
    if (this.mock) return;
    if (!silent) this.mcpServersLoading.set(true);
    try {
      this.mcpServers.set((await this.api.mcpServers()).map(server => this.toMcpCatalogServer(server)));
    } catch (e: any) {
      if (!silent) this.toast(`MCP server refresh failed: ${e.message}`);
    } finally {
      if (!silent) this.mcpServersLoading.set(false);
    }
  }

  async refreshRetainedMcpResources(): Promise<void> {
    if (this.mock) return;
    try {
      this.retainedMcpResources.set(await this.api.retainedMcpResources());
    } catch { /* retained data inventory is non-critical */ }
  }

  /** Create or update a catalog entry. Returns its id, or an empty string. */
  async saveCatalogMcpServer(input: McpCatalogServerInput, id?: string): Promise<string> {
    const duplicate = this.mcpServers().find(server =>
      server.id !== id && server.name.toLowerCase() === input.name.toLowerCase());
    if (duplicate) {
      this.toast(`MCP server name already exists: ${duplicate.name}`);
      return '';
    }
    if (!this.mock) {
      try {
        const saved = id
          ? await this.api.updateMcpServer(id, input)
          : await this.api.createMcpServer(input);
        const server = this.toMcpCatalogServer(saved);
        this.upsertMcpCatalogServer(server);
        if (this.mcpOperationActive(server.operationState)) void this.pollMcpOperation(server.id);
        return server.id;
      } catch (e: any) {
        this.toast(`MCP server save failed: ${e.message}`);
        return '';
      }
    }

    const existing = id ? this.mcpServerById(id) : null;
    const now = Date.now();
    const revision = (existing?.revision ?? 0) + 1;
    const serviceKey = existing?.serviceKey ?? (
      input.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || nid('mcp-service')
    );
    const running = existing?.runtimeState === 'running';
    const connectionUrl = input.kind === 'managed'
      ? (input.crossHostUrl || `http://${serviceKey}:${input.internalPort}${input.path ?? ''}`)
      : input.kind === 'external' ? input.url : null;
    const server: McpCatalogServer = {
      ...input,
      id: existing?.id ?? nid('mcp'),
      connectionUrl,
      desiredState: existing?.desiredState ?? 'stopped',
      runtimeState: existing?.runtimeState ?? (input.kind === 'managed' ? 'stopped' : 'unknown'),
      operationState: 'idle', operationError: null,
      checkStatus: existing?.checkStatus ?? 'unknown',
      checkError: existing?.checkError ?? null,
      checkedAt: existing?.checkedAt ?? null,
      latencyMs: existing?.latencyMs ?? null,
      revision,
      appliedRevision: running ? (existing?.appliedRevision ?? 0) : revision,
      pendingChanges: running,
      serviceKey: input.kind === 'managed' ? serviceKey : null,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    };
    this.upsertMcpCatalogServer(server);
    return server.id;
  }

  async deleteCatalogMcpServer(id: string): Promise<boolean> {
    const server = this.mcpServerById(id);
    if (!server) return false;
    if (!this.mock) {
      try {
        const response = await this.api.deleteMcpServer(id);
        if (response) this.upsertMcpCatalogServer(this.toMcpCatalogServer(response));
        await this.refreshMcpServers(true);
        void this.pollMcpOperation(id, true);
        return true;
      } catch (e: any) {
        this.toast(`MCP server delete failed: ${e.message}`);
        return false;
      }
    }

    const seen = new Set<string>();
    const volumes = [...server.volumes, ...server.supportServices.flatMap(service => service.volumes ?? [])]
      .filter(volume => !seen.has(volume.name) && seen.add(volume.name));
    this.retainedMcpResources.update(resources => [
      ...volumes.map((volume): McpRetainedResource => ({
        id: nid('mcp-volume'), serverId: server.id, serverName: server.name,
        hostId: server.hostId ?? 'dh-local', type: 'volume', name: volume.name,
        createdAt: Date.now(),
      })),
      ...resources,
    ]);
    this.mcpServers.update(servers => servers.filter(item => item.id !== id));
    return true;
  }

  async startCatalogMcpServer(id: string): Promise<boolean> {
    return this.runMcpOperation(id, 'start');
  }

  async stopCatalogMcpServer(id: string): Promise<boolean> {
    return this.runMcpOperation(id, 'stop');
  }

  async applyCatalogMcpServer(id: string): Promise<boolean> {
    return this.runMcpOperation(id, 'apply');
  }

  async checkCatalogMcpServer(id: string): Promise<boolean> {
    return this.runMcpOperation(id, 'check');
  }

  private async runMcpOperation(id: string, operation: 'start' | 'stop' | 'apply' | 'check'): Promise<boolean> {
    const server = this.mcpServerById(id);
    if (!server) return false;
    this.mcpServers.update(servers => servers.map(item => item.id === id ? {
      ...item,
      operationState: operation === 'check' ? item.operationState : ({
        start: 'starting', stop: 'stopping', apply: 'applying',
      } as const)[operation],
      operationError: null,
      ...(operation === 'check' ? { checkStatus: 'checking' as const, checkError: null } : {}),
    } : item));

    if (!this.mock) {
      try {
        const response = operation === 'start' ? await this.api.startMcpServer(id)
          : operation === 'stop' ? await this.api.stopMcpServer(id)
          : operation === 'apply' ? await this.api.applyMcpServer(id)
          : await this.api.checkMcpServer(id);
        if (response) this.upsertMcpCatalogServer(this.toMcpCatalogServer(response));
        else await this.refreshMcpServers(true);
        if (operation !== 'check') void this.pollMcpOperation(id);
        return operation !== 'check' || this.mcpServerById(id)?.checkStatus === 'connected';
      } catch (e: any) {
        this.mcpServers.update(servers => servers.map(item => item.id === id ? {
          ...item,
          operationState: operation === 'check' ? item.operationState : 'error',
          operationError: operation === 'check' ? item.operationError : e.message,
          ...(operation === 'check'
            ? { checkStatus: 'error' as const, checkError: e.message, checkedAt: Date.now(), latencyMs: null }
            : {}),
        } : item));
        this.toast(`MCP server ${operation} failed: ${e.message}`);
        return false;
      }
    }

    await Promise.resolve();
    this.mcpServers.update(servers => servers.map(item => {
      if (item.id !== id) return item;
      if (operation === 'check') return {
        ...item, checkStatus: 'connected' as const, checkError: null,
        checkedAt: Date.now(), latencyMs: 24,
      };
      if (operation === 'stop') return {
        ...item, desiredState: 'stopped' as const, runtimeState: 'stopped' as const,
        operationState: 'idle', operationError: null,
      };
      return {
        ...item, desiredState: 'running' as const, runtimeState: 'running' as const,
        operationState: 'idle', operationError: null,
        appliedRevision: item.revision, pendingChanges: false,
      };
    }));
    return true;
  }

  private readonly mcpOperationPolls = new Set<string>();

  private mcpOperationActive(state: string): boolean {
    return !['', 'idle', 'none', 'error', 'failed', 'complete', 'completed'].includes(state.toLowerCase());
  }

  /** Polls only while a lifecycle operation is active. Delete polling stops
   *  once the entry disappears from the catalog. */
  private async pollMcpOperation(id: string, deleting = false): Promise<void> {
    if (this.mock || this.mcpOperationPolls.has(id)) return;
    this.mcpOperationPolls.add(id);
    try {
      // Image pulls (notably Playwright) can take several minutes on a cold
      // host. Keep polling through the backend's ten-minute Compose timeout.
      for (let attempt = 0; attempt < 420; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 1_500));
        await this.refreshMcpServers(true);
        const server = this.mcpServerById(id);
        if (!server || !this.mcpOperationActive(server.operationState)) break;
      }
      if (deleting) await this.refreshRetainedMcpResources();
    } finally {
      this.mcpOperationPolls.delete(id);
    }
  }

  async mcpServerLogTail(id: string, tail = 100): Promise<LogEntry[]> {
    const server = this.mcpServerById(id);
    if (!server || server.kind !== 'managed') return [];
    if (this.mock) {
      const now = Date.now();
      return [
        { ts: now - 3_000, level: 'info' as const, source: server.serviceKey ?? server.name, agentId: null,
          msg: server.runtimeState === 'running' ? `MCP endpoint ready at ${server.connectionUrl}` : 'container is stopped' },
        { ts: now - 8_000, level: 'debug' as const, source: 'compose', agentId: null,
          msg: `project mission-control-mcp · revision ${server.appliedRevision}` },
      ].slice(0, tail);
    }
    const lines = await this.api.mcpServerLogs(id, tail);
    return lines.map(line => ({ ...line, agentId: null })).sort((a, b) => b.ts - a.ts);
  }

  async purgeRetainedMcpResource(id: string): Promise<boolean> {
    if (!this.mock) {
      try {
        await this.api.purgeRetainedMcpResource(id);
      } catch (e: any) {
        this.toast(`retained resource purge failed: ${e.message}`);
        return false;
      }
    }
    this.retainedMcpResources.update(resources => resources.filter(resource => resource.id !== id));
    return true;
  }

  // ── image tags ───────────────────────────────────────────────────────

  imageTags(hostId: string): Promise<ApiImageTags> {
    if (this.mock) {
      const catalog = this.mockImageCatalog(hostId);
      return Promise.resolve({
        repository: catalog.repository,
        tags: catalog.tags.map(t => t.tag),
        entries: catalog.tags.map(t => ({ tag: t.tag, pulled: t.pulled, remote: true })),
      });
    }
    return this.api.imageTags(hostId);
  }

  /**
   * Merged registry + local tags per docker host. Advisory data: a failed
   * refresh keeps the last catalog and never toasts.
   */
  readonly imageCatalog = signal<Record<string, ImageCatalog>>({});

  private readonly catalogInFlight = new Set<string>();
  private static readonly CATALOG_TTL = 300_000;

  async refreshImageCatalog(hostId: string, force = false): Promise<void> {
    if (!hostId || this.catalogInFlight.has(hostId)) return;
    const known = this.imageCatalog()[hostId];
    if (!force && known && Date.now() - known.fetchedAt < HermesStore.CATALOG_TTL) return;
    this.catalogInFlight.add(hostId);
    try {
      const catalog = this.mock
        ? this.mockImageCatalog(hostId)
        : this.toImageCatalog(await this.api.imageTags(hostId));
      this.imageCatalog.update(m => ({ ...m, [hostId]: catalog }));
    } catch {
      /* registry or daemon hiccup — keep the last catalog */
    } finally {
      this.catalogInFlight.delete(hostId);
    }
  }

  /** Refreshes every connected host that actually runs containers. */
  async refreshImageCatalogs(force = false): Promise<void> {
    const hosted = new Set(this.containers().map(c => c.hostId));
    const ids = this.dockerHosts()
      .filter(h => h.status === 'connected' && hosted.has(h.id))
      .map(h => h.id);
    await this.mapPool(ids, 4, id => this.refreshImageCatalog(id, force));
  }

  private toImageCatalog(r: ApiImageTags): ImageCatalog {
    // a backend without `entries` only ever reported local tags, so treat them as pulled
    const pulled = new Set(r.entries?.filter(e => e.pulled).map(e => e.tag) ?? r.tags);
    return {
      repository: r.repository,
      tags: (r.entries?.map(e => e.tag) ?? r.tags).map(tag => ({ tag, pulled: pulled.has(tag) })),
      registryStatus: r.registryStatus ?? 'unavailable',
      fetchedAt: Date.now(),
    };
  }

  private mockImageCatalog(hostId: string): ImageCatalog {
    const running = new Set(this.containers().filter(c => c.hostId === hostId).map(c => c.version));
    return {
      repository: this.containers()[0]?.image ?? 'nousresearch/hermes-agent',
      tags: seedImageTags().map(tag => ({ tag, pulled: tag === 'latest' || running.has(tag) })),
      registryStatus: 'ok',
      fetchedAt: Date.now(),
    };
  }

  // ── model provider actions (ollama registry) ───────────────────────────
  async refreshModelProviders(): Promise<void> {
    try {
      this.modelProviders.set(await this.api.modelProviders());
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Loads the LLM provider registry; keeps the bootstrap mirror on failure. */
  async refreshProviderRegistry(): Promise<void> {
    if (this.mock) return;
    try {
      const list = await this.api.modelProviderRegistry();
      if (list.length) this.llmProviders.set(list);
    } catch { /* keep DEFAULT_LLM_PROVIDERS */ }
  }

  addModelProvider(name: string, url: string): void {
    if (!this.mock) {
      this.api.addModelProvider(name, url)
        .then(() => this.refreshModelProviders())
        .catch(e => this.toast(`add provider failed: ${e.message}`));
      return;
    }
    const provider: ModelProvider = {
      id: nid('mp'), name, url, kind: 'ollama',
      status: 'unknown', version: null, detail: null,
    };
    this.modelProviders.update(ps => [...ps, provider]);
    this.probeModelProvider(provider.id);
  }

  removeModelProvider(id: string): void {
    if (!this.mock) {
      this.api.deleteModelProvider(id)
        .then(() => this.refreshModelProviders())
        .catch(e => this.toast(`remove provider failed: ${e.message}`));
      return;
    }
    this.modelProviders.update(ps => ps.filter(p => p.id !== id));
  }

  checkModelProvider(id: string): void {
    this.modelProviders.update(ps => ps.map(p => p.id === id ? { ...p, status: 'unknown' as const } : p));
    if (!this.mock) {
      this.api.checkModelProvider(id)
        .then(provider => this.modelProviders.update(ps => ps.map(p => p.id === id ? provider : p)))
        .catch(e => {
          this.toast(`provider check failed: ${e.message}`);
          this.refreshModelProviders();
        });
      return;
    }
    this.probeModelProvider(id);
  }

  /** Simulated ollama ping — mock mode only; live mode asks the backend. */
  private probeModelProvider(id: string): void {
    setTimeout(() => {
      this.modelProviders.update(ps => ps.map(p => {
        if (p.id !== id) return p;
        const ok = Math.random() > 0.15;
        return ok
          ? { ...p, status: 'connected' as const, version: '0.6.x', detail: null }
          : { ...p, status: 'error' as const, version: null,
              detail: 'connection refused — is ollama listening on that address?' };
      }));
    }, 800);
  }

  providerModels(id: string): Promise<OllamaModel[]> {
    if (this.mock) {
      const yesterday = Date.now() - 86_400_000;
      return Promise.resolve([
        { name: 'gemma3:4b', sizeBytes: 3_300_000_000, family: 'gemma3', parameterSize: '4.3B', modifiedAt: yesterday },
        { name: 'qwen3:8b', sizeBytes: 5_200_000_000, family: 'qwen3', parameterSize: '8.2B', modifiedAt: yesterday },
      ]);
    }
    return this.api.providerModels(id).catch(e => {
      this.toast(`model list failed: ${e.message}`);
      return [];
    });
  }

  pullModel(id: string, name: string): Promise<void> {
    if (this.mock) {
      this.toast('mock mode — not pulling');
      return Promise.resolve();
    }
    return this.api.pullProviderModel(id, name)
      .catch(e => this.toast(`pull failed: ${e.message}`));
  }

  deleteProviderModel(id: string, name: string): Promise<void> {
    if (this.mock) {
      this.toast('mock mode — not deleting');
      return Promise.resolve();
    }
    return this.api.deleteProviderModel(id, name)
      .catch(e => this.toast(`model delete failed: ${e.message}`));
  }

  pullStatus(id: string): Promise<ApiPullState[]> {
    if (this.mock) return Promise.resolve([]);
    return this.api.pullStatus(id).catch(() => []);
  }

  // ── model catalog ──────────────────────────────────────────────────────
  async modelCatalog(provider: string): Promise<string[]> {
    const fallback = FALLBACK_MODELS[provider] ?? [];
    if (this.mock) return fallback;
    try {
      return (await this.api.modelCatalog(provider)).models;
    } catch {
      return fallback;
    }
  }

  /** Fetch the catalog straight from the provider API using a key — live only. */
  async modelCatalogLive(provider: string, apiKey: string): Promise<string[]> {
    if (this.mock) return this.modelCatalog(provider);
    try {
      return (await this.api.modelCatalogLive(provider, apiKey)).models;
    } catch {
      return this.modelCatalog(provider);
    }
  }

  // ── container actions ──────────────────────────────────────────────────
  selectContainer(id: string): void {
    this.selectedContainerId.set(id);
    this.logsLoading.set(false);
    this.logsUpdatedAt.set(null);
    this.logsError.set(null);
    if (!this.mock) void this.pollLogs();
  }

  /** Deploys a container and resolves only after refreshed inventory contains it. */
  async deployContainer(name: string, version: string, profileNames: string[], hostId = 'dh-local'): Promise<string> {
    if (!this.mock) {
      try {
        const r = await this.api.deploy(hostId, name, version, profileNames);
        await new Promise(resolve => setTimeout(resolve, 600));
        await this.refreshContainers();
        this.selectContainer(r.id);
        return r.id;
      } catch (e: any) {
        this.toast(`deploy failed: ${e.message}`);
        return '';
      }
    }
    const id = nid('c');
    const container: HermesContainer = {
      id, name, shortId: Math.random().toString(16).slice(2, 9), hostId, status: 'running',
      image: 'nousresearch/hermes-agent', version,
      startedAt: Date.now(),
      cpu: 8, ram: 512, ramTotal: 4096, disk: 1.2, diskTotal: 40,
      netIn: 5, netOut: 2,
      cpuHist: Array(60).fill(8), ramHist: Array(60).fill(512), netHist: Array(60).fill(7),
    };
    this.containers.update(cs => [...cs, container]);
    this.logsByContainer.update(m => ({
      ...m,
      [id]: [{ ts: Date.now(), level: 'info', source: 'system', agentId: null, msg: `container deployed (${version})` }],
    }));
    for (const p of profileNames.filter(Boolean)) {
      void this.createAgent(id, p, 'anthropic', 'claude-fable-5', 'sk-ant-new');
    }
    return id;
  }

  setContainerStatus(id: string, status: ContainerStatus): void {
    if (!this.mock) {
      const container = this.containers().find(c => c.id === id);
      if (!container) return;
      const call = status === 'running'
        ? this.api.startContainer(container.hostId, id)
        : this.api.stopContainer(container.hostId, id);
      call
        .then(() => setTimeout(() => this.refreshContainers(), 700))
        .catch(e => this.toast(`${status === 'running' ? 'start' : 'stop'} failed: ${e.message}`));
      return;
    }
    this.containers.update(cs => cs.map(c => c.id !== id ? c : {
      ...c, status,
      startedAt: status === 'running' ? Date.now() : c.startedAt,
      ...(status === 'stopped' ? { cpu: 0, ram: 0, netIn: 0, netOut: 0 } : {}),
    }));
    this.appendLog(id, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: status === 'running' ? 'container started' : `container ${status}`,
    });
    if (status === 'stopped') {
      this.agents.update(as => as.map(a => a.containerId === id ? { ...a, state: 'dormant' } : a));
    }
  }

  /**
   * Recreates `id` on `version`. The backend pulls the tag if needed, then
   * replaces the container against the same data volume, so profiles, souls,
   * skills and credentials survive. **The container id changes** — callers
   * holding an id must re-read it. Resolves to the new id, or '' on failure.
   */
  async updateContainer(id: string, version: string): Promise<string> {
    const container = this.containers().find(c => c.id === id);
    if (!container || !version || version === container.version) return '';
    const wasSelected = this.selectedContainerId() === id;

    if (!this.mock) {
      try {
        const r = await this.api.updateContainer(container.hostId, id, version);
        await this.refreshContainers();
        if (wasSelected) this.selectContainer(r.id);
        void this.refreshImageCatalog(container.hostId, true);   // the tag is pulled now
        return r.id;
      } catch (e: any) {
        this.toast(`update failed: ${e.message}`);
        await this.refreshContainers();   // the recreate may have half-landed
        return '';
      }
    }

    const newId = nid('c');
    const priorLogs = this.logsByContainer()[id] ?? [];
    this.containers.update(cs => cs.map(c => c.id !== id ? c : {
      ...c, id: newId, shortId: Math.random().toString(16).slice(2, 9),
      version, status: 'running', startedAt: Date.now(),
      cpuHist: [], ramHist: [], netHist: [],   // fresh container, no telemetry history
    }));
    // everything keyed by container id follows the new identity; agent ids are
    // stable because the profiles live in the volume that was reattached
    this.agents.update(as => as.map(a => a.containerId === id
      ? { ...a, containerId: newId, state: a.state === 'dormant' ? 'idle' : a.state } : a));
    this.jobs.update(js => js.map(j => j.containerId === id ? { ...j, containerId: newId } : j));
    this.tasks.update(ts => ts.map(t => t.containerId === id ? { ...t, containerId: newId } : t));
    this.logsByContainer.update(m => {
      const next = { ...m };
      delete next[id];
      next[newId] = priorLogs;
      return next;
    });
    this.appendLog(newId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: `container recreated on ${version} — data volume reattached`,
    });
    if (wasSelected) this.selectContainer(newId);
    return newId;
  }

  async removeContainer(id: string): Promise<boolean> {
    if (!this.mock) {
      const container = this.containers().find(c => c.id === id);
      if (!container) return false;
      try {
        await this.api.removeContainer(container.hostId, id);
        if (this.selectedContainerId() === id) this.selectedContainerId.set('');
        await this.refreshContainers();
        return true;
      } catch (e: any) {
        this.toast(`remove failed: ${e.message}`);
        await this.refreshContainers(); // removal may have succeeded before volume cleanup failed
        return false;
      }
    }
    const agentIds = new Set(this.agents().filter(a => a.containerId === id).map(a => a.id));
    this.containers.update(cs => cs.filter(c => c.id !== id));
    this.agents.update(as => as.filter(a => a.containerId !== id));
    this.jobs.update(js => js.filter(j => j.containerId !== id));
    this.tasks.update(ts => ts.filter(t => t.containerId !== id));
    this.webhooks.update(ws => ws.filter(w => !agentIds.has(w.agentId)));
    if (this.selectedContainerId() === id) {
      this.selectedContainerId.set(this.containers()[0]?.id ?? '');
    }
    return true;
  }

  // ── agent actions ────────────────────────────────────────────────────
  async createAgent(
    containerId: string,
    name: string,
    provider: string,
    model: string,
    apiKey: string,
    cloneFromId?: string,
    baseUrl?: string,
    templateId?: string,
    auxiliary?: ApiAuxiliaryModel,
  ): Promise<string> {
    if (!this.mock) {
      const container = this.containers().find(c => c.id === containerId);
      if (!container) return '';
      const cloneFromName = cloneFromId ? this.agentById(cloneFromId)?.name : undefined;
      try {
        const created = await this.api.createAgent(
          container.hostId,
          containerId,
          name,
          provider,
          model,
          apiKey,
          cloneFromName,
          baseUrl,
          templateId || undefined,
          auxiliary,
        );
        const agent = this.toAgentProfile(created);
        this.agents.update(as => [...as.filter(a => a.id !== agent.id), agent]);
        return agent.id;
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        this.toast(`create profile failed: ${message}`);
        return '';
      }
    }
    const id = nid('a');
    const src = cloneFromId ? this.agentById(cloneFromId) : null;
    const apiKeyMasked = maskTail(apiKey) || '…';
    const agent: AgentProfile = {
      id, containerId, name,
      role: src ? `Clone of ${src.name}` : 'New profile',
      state: 'idle', provider, model,
      apiKeyMasked, cwd: `/home/hermes/${name}`,
      soul: src ? src.soul : `# SOUL.md — ${name}\n\nDescribe this agent's personality and directives.\n`,
      memoryMd: '# MEMORY.md\n\n(empty)\n',
      configYaml: `# config.yaml — ${name}\nprovider: ${provider}\nmodel: ${model}\nterminal:\n  cwd: /home/hermes/${name}\n`,
      skills: src ? src.skills.map(s => ({ ...s })) : [
        { id: nid('s'), name: 'daily-briefing', source: 'bundled', version: '2.1.0', description: 'Compile and deliver scheduled briefings', enabled: true },
        { id: nid('s'), name: 'web-research', source: 'bundled', version: '2.1.0', description: 'Multi-source search and synthesis', enabled: true },
      ],
      mcp: [], integrations: [{ kind: 'filesystem', status: 'up', detail: `/home/hermes/${name} (rw)` }],
      sessions: [], msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: Date.now(),
    };
    const tmpl = templateId ? this.templateById(templateId) : null;
    if (tmpl) {
      if (agent.role === 'New profile') agent.role = `From ${tmpl.name}`;
      if (tmpl.soul) agent.soul = tmpl.soul;
      if (tmpl.memory) agent.memoryMd = tmpl.memory;
      agent.skills = tmpl.skills.map(s => ({
        id: nid('s'), name: s, source: 'bundled' as const, version: '1.0.0', description: '', enabled: true,
      }));
      agent.mcp = tmpl.mcpServers.map(m => ({
        id: nid('m'), name: m.name, transport: m.transport,
        enabled: m.enabled, origin: 'custom' as const, catalogServerId: null,
        syncedRevision: null, catalogRevision: null, updateAvailable: false,
        status: m.enabled ? 'unknown' as const : 'disabled' as const,
        tools: 0, latencyMs: null, url: m.url, command: m.command, args: m.args,
      }));
    }
    this.agents.update(as => [...as, agent]);
    this.appendLog(containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: id,
      msg: `profile "${name}" created${src ? ` (cloned from ${src.name})` : ''}`,
    });
    return id;
  }

  /** Profile-scoped supervised gateway log. Unlike Docker logs, these entries
   * have an authoritative agent/profile identity. */
  async agentLogTail(agentId: string, tail = 100): Promise<LogEntry[]> {
    const agent = this.agentById(agentId);
    if (!agent) return [];
    if (this.mock) {
      return this.containerLogs()
        .filter(line => line.agentId === agentId)
        .slice(0, tail);
    }
    const container = this.containers().find(c => c.id === agent.containerId);
    if (!container) return [];
    const lines = await this.api.agentLogs(container.hostId, agent.containerId, agent.name, tail);
    return lines
      .map(line => ({ ...line, agentId }))
      .sort((a, b) => b.ts - a.ts);
  }

  removeAgent(id: string): void {
    const agent = this.agentById(id);
    if (!agent) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === agent.containerId);
      if (!container) return;
      this.api.deleteAgent(container.hostId, agent.containerId, agent.name)
        .then(() => {
          this.agents.update(as => as.filter(a => a.id !== id));
          this.jobs.update(js => js.filter(j => j.agentId !== id));
          this.tasks.update(ts => ts.filter(t => t.agentId !== id));
          this.webhooks.update(ws => ws.filter(w => w.agentId !== id));
        })
        .catch(e => this.toast(`remove profile failed: ${e.message}`));
      return;
    }
    this.agents.update(as => as.filter(a => a.id !== id));
    this.jobs.update(js => js.filter(j => j.agentId !== id));
    this.tasks.update(ts => ts.filter(t => t.agentId !== id));
    this.webhooks.update(ws => ws.filter(w => w.agentId !== id));
    this.appendLog(agent.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: `profile "${agent.name}" deleted`,
    });
  }

  private patchAgent(id: string, patch: Partial<AgentProfile>): void {
    this.agents.update(as => as.map(a => a.id === id ? { ...a, ...patch } : a));
  }

  /** Live profile mutations all return the refreshed profile — apply it, toast on failure. */
  private applyAgentCall(agentId: string, label: string, call: Promise<ApiAgentProfile>): void {
    call
      .then(updated => this.patchAgent(agentId, this.toAgentProfile(updated)))
      .catch(e => this.toast(`${label} failed: ${e.message}`));
  }

  async updateSoul(id: string, soul: string): Promise<boolean> {
    const a = this.agentById(id);
    if (!a) return false;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return false;
      try {
        await this.api.updateSoul(container.hostId, a.containerId, a.name, soul);
        if (this.agentById(id)) this.patchAgent(id, { soul });
        return true;
      } catch (e: any) {
        this.toast(`SOUL.md save failed: ${e.message}`);
        return false;
      }
    }
    this.patchAgent(id, { soul });
    this.appendLog(a.containerId, { ts: Date.now(), level: 'info', source: 'system', agentId: id, msg: 'SOUL.md updated via dashboard' });
    return true;
  }

  async updateAgentConfig(agentId: string, configYaml: string): Promise<boolean> {
    const a = this.agentById(agentId);
    if (!a) return false;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.updateAgentConfig(container.hostId, a.containerId, a.name, configYaml);
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`config save failed: ${e.message}`);
        return false;
      }
    }
    this.patchAgent(agentId, { configYaml });
    this.appendLog(a.containerId, { ts: Date.now(), level: 'info', source: 'system', agentId, msg: 'config.yaml updated via dashboard' });
    return true;
  }

  toggleSkill(agentId: string, skillId: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      const skill = a.skills.find(s => s.id === skillId);
      if (!container || !skill) return;
      const enabled = !skill.enabled;
      this.applyAgentCall(agentId, 'skill update',
        this.api.setSkillEnabled(container.hostId, a.containerId, a.name, skill.name, enabled));
      return;
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, skills: x.skills.map(s => s.id === skillId ? { ...s, enabled: !s.enabled } : s),
    }));
  }

  addSkill(agentId: string, skill: Omit<SkillRef, 'id'>): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return;
      this.applyAgentCall(agentId, 'skill install',
        this.api.installSkill(container.hostId, a.containerId, a.name, skill.name));
      return;
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, skills: [...x.skills, { ...skill, id: nid('s') }],
    }));
  }

  removeSkill(agentId: string, skillId: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      const skill = a.skills.find(s => s.id === skillId);
      if (!container || !skill) return;
      this.applyAgentCall(agentId, 'skill uninstall',
        this.api.uninstallSkill(container.hostId, a.containerId, a.name, skill.name));
      return;
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, skills: x.skills.filter(s => s.id !== skillId),
    }));
  }

  /** mock-mode SKILL.md store; edits persist in-session over the seeded bodies */
  private readonly mockSkillBodies: Record<string, string> = this.mock ? seedSkillBodies() : {};

  /** Load a skill's SKILL.md body + file list for the explore/edit viewer. */
  async getSkillContent(agentId: string, skill: SkillRef): Promise<SkillContent | null> {
    const a = this.agentById(agentId);
    if (!a) return null;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return null;
      try {
        const c = await this.api.skillContent(container.hostId, a.containerId, a.name, skill.name);
        return { name: c.name, path: c.path, body: c.body, files: c.files ?? [] };
      } catch (e: any) {
        this.toast(`load skill failed: ${e.message}`);
        return null;
      }
    }
    const body = this.mockSkillBodies[skill.name] ?? this.synthSkillBody(skill);
    return {
      name: skill.name,
      path: `~/.hermes/profiles/${a.name}/skills/${skill.name}`,
      body,
      files: ['SKILL.md'],
    };
  }

  /** Persist an edited SKILL.md. Returns true on success. */
  async saveSkillContent(agentId: string, skill: SkillRef, body: string): Promise<boolean> {
    const a = this.agentById(agentId);
    if (!a) return false;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.updateSkillContent(container.hostId, a.containerId, a.name, skill.name, body);
        // guard: agent may have been removed while the PUT was in flight
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`save skill failed: ${e.message}`);
        return false;
      }
    }
    this.mockSkillBodies[skill.name] = body;
    this.appendLog(a.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId,
      msg: `skill ${skill.name} SKILL.md updated via dashboard`,
    });
    return true;
  }

  /** Fallback SKILL.md when no seeded body exists for a mock skill. */
  private synthSkillBody(skill: SkillRef): string {
    return `---\nname: ${skill.name}\ndescription: ${skill.description}\nversion: ${skill.version}\nsource: ${skill.source}\n---\n\n# ${skill.name}\n\n${skill.description}\n`;
  }

  async addMcp(
    agentId: string,
    name: string,
    transport: McpServer['transport'],
    opts?: { url?: string; command?: string; args?: string },
  ): Promise<boolean> {
    const a = this.agentById(agentId);
    if (!a) return false;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.addMcpServer(container.hostId, a.containerId, a.name, {
          name,
          transport,
          url: opts?.url,
          command: opts?.command,
          args: opts?.args,
        });
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`mcp add failed: ${e.message}`);
        return false;
      }
    }
    // mock upsert: replace a same-named server (edit), else append
    const server: McpServer = {
      id: nid('m'), name, transport, enabled: true, origin: 'custom', catalogServerId: null,
      syncedRevision: null, catalogRevision: null, updateAvailable: false, status: 'unknown',
      tools: 0, latencyMs: null, error: null, checkedAt: null,
      url: opts?.url, command: opts?.command, args: opts?.args,
    };
    this.agents.update(as => as.map(x => {
      if (x.id !== agentId) return x;
      const existing = x.mcp.find(m => m.name === name);
      const mcp = existing
        ? x.mcp.map(m => m.name === name ? { ...server, id: m.id } : m)
        : [...x.mcp, server];
      return { ...x, mcp };
    }));
    return true;
  }

  /** Atomic direct-server edit/rename. Catalog-linked servers must be unlinked
   *  by the caller first, which keeps registry synchronization explicit. */
  async updateMcp(
    agentId: string,
    oldName: string,
    name: string,
    transport: McpServer['transport'],
    opts?: { url?: string; command?: string; args?: string },
  ): Promise<boolean> {
    const agent = this.agentById(agentId);
    const existing = agent?.mcp.find(server => server.name === oldName);
    if (!agent || !existing) return false;
    if (oldName !== name && agent.mcp.some(server => server.name === name)) {
      this.toast(`MCP alias already exists: ${name}`);
      return false;
    }
    if (!this.mock) {
      const container = this.containers().find(item => item.id === agent.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.updateAgentMcpServer(
          container.hostId, agent.containerId, agent.name, oldName,
          { name, transport, url: opts?.url, command: opts?.command, args: opts?.args, enabled: existing.enabled },
        );
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`MCP update failed: ${e.message}`);
        return false;
      }
    }
    this.agents.update(agents => agents.map(item => item.id !== agentId ? item : ({
      ...item,
      mcp: item.mcp.map(server => server.name !== oldName ? server : ({
        ...server, name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
        status: server.enabled ? 'unknown' as const : 'disabled' as const,
        tools: 0, latencyMs: null, error: null, checkedAt: null,
      })),
    })));
    return true;
  }

  async setMcpEnabled(agentId: string, serverName: string, enabled: boolean): Promise<boolean> {
    const agent = this.agentById(agentId);
    if (!agent?.mcp.some(server => server.name === serverName)) return false;
    if (!this.mock) {
      const container = this.containers().find(item => item.id === agent.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.setAgentMcpEnabled(
          container.hostId, agent.containerId, agent.name, serverName, enabled,
        );
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`MCP ${enabled ? 'connect' : 'disconnect'} failed: ${e.message}`);
        return false;
      }
    }
    this.agents.update(agents => agents.map(item => item.id !== agentId ? item : ({
      ...item,
      mcp: item.mcp.map(server => server.name !== serverName ? server : ({
        ...server, enabled, status: enabled ? 'unknown' as const : 'disabled' as const,
        ...(enabled ? { error: null } : {}),
      })),
    })));
    return true;
  }

  /** Starts a stopped managed catalog server and waits for the real runtime
   *  state before writing any Agent configuration. */
  async connectCatalogMcp(agentId: string, serverId: string, alias: string): Promise<boolean> {
    const agent = this.agentById(agentId);
    let catalog = this.mcpServerById(serverId);
    if (!agent || !catalog || !alias.trim()) return false;
    const agentContainer = this.containers().find(item => item.id === agent.containerId);
    if (!agentContainer) return false;
    if (agent.mcp.some(server => server.name === alias)) {
      this.toast(`MCP alias already exists: ${alias}`);
      return false;
    }
    if (catalog.kind === 'managed' && catalog.hostId !== agentContainer.hostId && !catalog.crossHostUrl) {
      this.toast(`MCP server ${catalog.name} needs an explicit cross-host URL for this Agent`);
      return false;
    }

    if (catalog.kind === 'managed' && catalog.runtimeState !== 'running') {
      if (!(await this.startCatalogMcpServer(serverId))) return false;
      if (!this.mock) {
        const deadline = Date.now() + 10 * 60_000;
        while (Date.now() < deadline) {
          await this.refreshMcpServers(true);
          catalog = this.mcpServerById(serverId);
          if (!catalog) return false;
          if (catalog.runtimeState === 'running') break;
          if (catalog.runtimeState === 'error' || catalog.operationState === 'error' || catalog.operationError) {
            this.toast(`MCP server start failed: ${catalog.operationError ?? catalog.runtimeState}`);
            return false;
          }
          await new Promise(resolve => setTimeout(resolve, 1_500));
        }
        if (catalog.runtimeState !== 'running') {
          this.toast(`MCP server start timed out: ${catalog.name}`);
          return false;
        }
      }
    }

    if (!this.mock) {
      try {
        const updated = await this.api.connectAgentCatalogMcp(
          agentContainer.hostId, agent.containerId, agent.name, serverId, alias.trim(),
        );
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`MCP catalog connect failed: ${e.message}`);
        return false;
      }
    }

    const linked = this.catalogMcpDefinition(catalog, alias.trim(), true);
    this.agents.update(agents => agents.map(item => item.id === agentId
      ? { ...item, mcp: [...item.mcp, linked] }
      : item));
    return true;
  }

  async syncCatalogMcp(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agentById(agentId);
    const linked = agent?.mcp.find(server => server.name === alias && server.catalogServerId);
    if (!agent || !linked?.catalogServerId) return false;
    if (!this.mock) {
      const container = this.containers().find(item => item.id === agent.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.syncAgentCatalogMcp(container.hostId, agent.containerId, agent.name, alias);
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`MCP sync failed: ${e.message}`);
        return false;
      }
    }
    const catalog = this.mcpServerById(linked.catalogServerId);
    if (!catalog) return false;
    const synced = this.catalogMcpDefinition(catalog, alias, linked.enabled, linked.id);
    this.agents.update(agents => agents.map(item => item.id !== agentId ? item : ({
      ...item, mcp: item.mcp.map(server => server.name === alias ? synced : server),
    })));
    return true;
  }

  async unlinkCatalogMcp(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agentById(agentId);
    const linked = agent?.mcp.find(server => server.name === alias);
    if (!agent || !linked) return false;
    if (!this.mock) {
      const container = this.containers().find(item => item.id === agent.containerId);
      if (!container) return false;
      try {
        const updated = await this.api.unlinkAgentCatalogMcp(container.hostId, agent.containerId, agent.name, alias);
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`MCP customize failed: ${e.message}`);
        return false;
      }
    }
    this.agents.update(agents => agents.map(item => item.id !== agentId ? item : ({
      ...item,
      mcp: item.mcp.map(server => server.name !== alias ? server : ({
        ...server, origin: 'custom' as const, catalogServerId: null,
        syncedRevision: null, catalogRevision: null, updateAvailable: false,
      })),
    })));
    return true;
  }

  private catalogMcpDefinition(
    catalog: McpCatalogServer, alias: string, enabled: boolean, id = nid('m'),
  ): McpServer {
    return {
      id, name: alias, transport: catalog.transport, enabled, origin: 'catalog',
      catalogServerId: catalog.id, syncedRevision: catalog.revision,
      catalogRevision: catalog.revision, updateAvailable: false,
      status: enabled ? 'unknown' : 'disabled', tools: 0, latencyMs: null,
      error: null, checkedAt: null,
      url: catalog.kind === 'stdio' ? undefined : (catalog.connectionUrl ?? catalog.url ?? undefined),
      command: catalog.kind === 'stdio' ? (catalog.stdioCommand ?? undefined) : undefined,
      args: catalog.kind === 'stdio' && catalog.args.length ? catalog.args.join(' ') : undefined,
    };
  }

  /** Retest a single MCP server's reachability. */
  async testMcp(agentId: string, serverName: string): Promise<boolean> {
    const a = this.agentById(agentId);
    if (!a) return false;
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, mcp: x.mcp.map(m => m.name === serverName && m.status !== 'disabled'
        ? { ...m, status: 'checking' as const, error: null } : m),
    }));
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return false;
      try {
        const r = await this.api.testMcpServer(container.hostId, a.containerId, a.name, serverName);
        this.agents.update(as => as.map(x => x.id !== agentId ? x : ({
          ...x, mcp: x.mcp.map(m => m.name === serverName
            ? { ...m, status: r.status as any, tools: r.tools, latencyMs: r.latencyMs,
                error: r.error, checkedAt: r.checkedAt } : m),
        })));
        if (r.error) this.toast(`mcp ${serverName}: ${r.error}`);
        return r.status === 'connected';
      } catch (e: any) {
        this.toast(`mcp test failed: ${e.message}`);
        this.agents.update(as => as.map(x => x.id !== agentId ? x : ({
          ...x, mcp: x.mcp.map(m => m.name === serverName
            ? { ...m, status: 'error' as const, latencyMs: null,
                error: e.message, checkedAt: Date.now() } : m),
        })));
        return false;
      }
    }
    await new Promise(res => setTimeout(res, 700));
    const ok = Math.random() < 0.85;
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, mcp: x.mcp.map(m => m.name !== serverName ? m : {
        ...m, status: ok ? 'connected' : 'error',
        latencyMs: ok ? 30 + Math.floor(Math.random() * 200) : null,
        error: ok ? null : 'simulated endpoint unreachable', checkedAt: Date.now(),
      }),
    }));
    return ok;
  }

  async removeMcp(agentId: string, mcpId: string): Promise<boolean> {
    const a = this.agentById(agentId);
    if (!a) return false;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      const server = a.mcp.find(m => m.id === mcpId);
      if (!container || !server) return false;
      try {
        const updated = await this.api.removeMcpServer(container.hostId, a.containerId, a.name, server.name);
        if (this.agentById(agentId)) this.patchAgent(agentId, this.toAgentProfile(updated));
        return true;
      } catch (e: any) {
        this.toast(`mcp remove failed: ${e.message}`);
        return false;
      }
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, mcp: x.mcp.filter(m => m.id !== mcpId),
    }));
    return true;
  }

  /** Simulated connectivity check — resolves each integration after a beat. */
  pingIntegrations(agentId: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return;
      this.api.integrations(container.hostId, a.containerId, a.name)
        .then(integrations => this.patchAgent(agentId, { integrations: integrations.map(i => ({
          kind: i.kind as any,
          status: i.status as any,
          detail: i.detail,
        })) }))
        .catch(e => this.toast(`integrations refresh failed: ${e.message}`));
      return;
    }
    setTimeout(() => {
      this.agents.update(as => as.map(x => x.id !== agentId ? x : {
        ...x,
        integrations: x.integrations.map<Integration>(i =>
          i.status === 'off' ? i : { ...i, status: Math.random() < 0.9 ? 'up' : 'degraded' }),
      }));
    }, 900);
  }

  // ── agent setup (.env) ─────────────────────────────────────────────────
  /** Mock-mode .env contents per agent; presence of a key = file exists. */
  private readonly mockEnv = new Map<string, Record<string, string>>();

  private buildMockSetup(agent: AgentProfile): ApiAgentSetup {
    const env = this.mockEnv.get(agent.id) ?? {};
    return {
      envPath: `/opt/data/profiles/${agent.name}/.env`,
      envExists: this.mockEnv.has(agent.id),
      apiKeys: MOCK_SETUP_API_KEYS.map(([label, envVar]) => ({
        label, envVar, set: !!env[envVar], masked: maskTail(env[envVar]) || null,
      })),
      authProviders: [
        { label: 'Nous Portal', ok: false, status: 'not logged in (run: hermes portal)', hint: 'hermes portal' },
        { label: 'OpenAI Codex', ok: false, status: 'not logged in (run: hermes codex)', hint: 'hermes codex' },
      ],
      apiKeyProviders: [],
      messaging: MOCK_SETUP_MESSAGING.map(([label, tokenVar, homeVar]) => ({
        label, tokenVar, homeVar,
        ok: !!env[tokenVar],
        status: env[tokenVar] ? 'configured' : 'not configured',
        homeChannel: homeVar ? env[homeVar] ?? null : null,
      })),
    };
  }

  /** Lists this agent's recorded sessions (mock returns the seeded list). */
  agentSessions(agentId: string): Promise<SessionInfo[] | null> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve(null);
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve(null);
      return this.api.agentSessions(container.hostId, a.containerId, a.name)
        .then(list => list.map(s => ({
          id: s.id, title: s.title, platform: s.platform,
          startedAt: s.startedAt, messages: s.messages,
          status: s.status === 'open' ? 'open' as const : 'closed' as const,
        })))
        .catch(e => { this.toast(`sessions load failed: ${e.message}`); return null; });
    }
    return Promise.resolve(a.sessions.map(s => ({ ...s })));
  }

  /** Chat history (messages) for a single session. */
  agentSessionMessages(agentId: string, sessionId: string): Promise<ChatMessage[] | null> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve(null);
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve(null);
      return this.api.agentSessionMessages(container.hostId, a.containerId, a.name, sessionId)
        .catch(e => { this.toast(`session load failed: ${e.message}`); return null; });
    }
    const s = a.sessions.find(x => x.id === sessionId);
    if (!s) return Promise.resolve(null);
    return Promise.resolve(buildMockChat(s));
  }

  /** Deletes a session file; mock removes it from the in-memory list. */
  deleteAgentSession(agentId: string, sessionId: string): Promise<void> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve();
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve();
      return this.api.deleteAgentSession(container.hostId, a.containerId, a.name, sessionId);
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, sessions: x.sessions.filter(s => s.id !== sessionId),
    }));
    return Promise.resolve();
  }

  /** Container-level auth-provider status (Nous Portal OAuth etc.) for the create
   *  modal — readable before an agent exists. Failures degrade to an empty list
   *  so the modal still works without the status badge. */
  authProviders(containerId: string): Promise<ApiSetupAuthProvider[]> {
    const container = this.containers().find(c => c.id === containerId);
    if (!container) return Promise.resolve([]);
    if (!this.mock) {
      return this.api.authProviders(container.hostId, containerId).catch(() => []);
    }
    return Promise.resolve([
      { label: 'Nous Portal', ok: false, status: 'not logged in (run: hermes portal)', hint: 'hermes portal' },
      { label: 'OpenAI Codex', ok: true, status: 'logged in', hint: null },
    ]);
  }

  agentSetup(agentId: string): Promise<ApiAgentSetup | null> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve(null);
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve(null);
      return this.api.agentSetup(container.hostId, a.containerId, a.name)
        .catch(e => {
          this.toast(`setup load failed: ${e.message}`);
          return null;
        });
    }
    return Promise.resolve(this.buildMockSetup(a));
  }

  /** Empty/null entry value removes that key from the .env file. */
  setAgentEnv(agentId: string, entries: Array<{ key: string; value: string | null }>): Promise<ApiAgentSetup | null> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve(null);
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve(null);
      return this.api.setAgentEnv(container.hostId, a.containerId, a.name, entries)
        .catch(e => {
          this.toast(`env save failed: ${e.message}`);
          return null;
        });
    }
    const env = { ...(this.mockEnv.get(a.id) ?? {}) };
    for (const { key, value } of entries) {
      if (value) env[key] = value;
      else delete env[key];
    }
    this.mockEnv.set(a.id, env);
    return Promise.resolve(this.buildMockSetup(a));
  }

  /** Writes the commented-out .env template only when the file is missing. */
  initAgentEnv(agentId: string): Promise<ApiAgentSetup | null> {
    const a = this.agentById(agentId);
    if (!a) return Promise.resolve(null);
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return Promise.resolve(null);
      return this.api.initAgentEnv(container.hostId, a.containerId, a.name)
        .catch(e => {
          this.toast(`env init failed: ${e.message}`);
          return null;
        });
    }
    if (!this.mockEnv.has(a.id)) this.mockEnv.set(a.id, {});
    return Promise.resolve(this.buildMockSetup(a));
  }

  // ── jobs ───────────────────────────────────────────────────────────────
  toggleJob(id: string): void {
    this.jobs.update(js => js.map(j => j.id === id ? { ...j, enabled: !j.enabled } : j));
  }

  updateJob(id: string, patch: Partial<CronJob>): void {
    this.jobs.update(js => js.map(j => j.id === id ? { ...j, ...patch } : j));
  }

  createJob(containerId: string, agentId: string, name: string, schedule: string, prompt: string, deliverTo: string): void {
    if (!this.mock) {
      this.toast('scheduling requires the hermes adapter — not available in live mode yet');
      return;
    }
    this.jobs.update(js => [...js, {
      id: nid('j'), containerId, agentId, name, schedule, prompt, deliverTo,
      enabled: true, lastRun: null, lastStatus: null,
      nextRun: Date.now() + 3_600_000,
    }]);
  }

  removeJob(id: string): void {
    this.jobs.update(js => js.filter(j => j.id !== id));
  }

  // ── board ──────────────────────────────────────────────────────────────
  moveTask(id: string, column: BoardColumn): void {
    const before = this.tasks();
    this.tasks.update(ts => ts.map(t => t.id === id ? { ...t, column } : t));
    if (!this.mock) {
      this.api.moveTask(id, column).catch(e => {
        this.tasks.set(before);   // optimistic move failed — roll back
        this.toast(`move failed: ${e.message}`);
      });
    }
  }

  // ── webhooks ───────────────────────────────────────────────────────────
  addWebhook(agentId: string, name: string, slug: string, events: string[]): void {
    if (!this.mock) {
      this.toast('webhooks require the hermes adapter — not available in live mode yet');
      return;
    }
    this.webhooks.update(ws => [...ws, {
      id: nid('w'), agentId, name, slug,
      secretMasked: 'whsec_…' + Math.random().toString(16).slice(2, 6),
      events, active: true, deliveries: [],
    }]);
  }

  toggleWebhook(id: string): void {
    this.webhooks.update(ws => ws.map(w => w.id === id ? { ...w, active: !w.active } : w));
  }

  removeWebhook(id: string): void {
    this.webhooks.update(ws => ws.filter(w => w.id !== id));
  }

  // ── profile templates (reusable agent blueprints) ──────────────────────────
  private toTemplate(api: ApiProfileTemplate): ProfileTemplate {
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

  async refreshTemplates(): Promise<void> {
    if (this.mock) return;
    try {
      this.profileTemplates.set((await this.api.profileTemplates()).map(t => this.toTemplate(t)));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  private upsertTemplate(t: ProfileTemplate): void {
    this.profileTemplates.update(ts => [t, ...ts.filter(x => x.id !== t.id)]);
  }

  /** Create (no id) or update (id) a template. Returns the id, or '' on failure. */
  async saveTemplate(input: ProfileTemplateInput, id?: string): Promise<string> {
    if (!this.mock) {
      try {
        const saved = id
          ? await this.api.updateProfileTemplate(id, input)
          : await this.api.createProfileTemplate(input);
        this.upsertTemplate(this.toTemplate(saved));
        return saved.id;
      } catch (e: any) {
        this.toast(`save template failed: ${e.message}`);
        return '';
      }
    }
    const now = Date.now();
    const existing = id ? this.templateById(id) : null;
    const prior = new Map((existing?.secrets ?? []).map(s => [s.key, s]));
    const secrets = input.secrets
      .filter(s => s.key.trim())
      .map(s => {
        if (s.value) return { key: s.key, set: true, recoverable: true };
        return prior.get(s.key) ?? { key: s.key, set: false, recoverable: false };
      });
    const tmpl: ProfileTemplate = {
      id: id ?? nid('pt'),
      name: input.name, description: input.description, provider: input.provider, model: input.model,
      baseUrl: input.baseUrl, cwd: input.cwd, soul: input.soul, memory: input.memory,
      skills: input.skills.filter(s => s.trim()),
      mcpServers: input.mcpServers.filter(m => m.name.trim()),
      secrets,
      createdAt: existing?.createdAt ?? now, updatedAt: now,
    };
    this.upsertTemplate(tmpl);
    return tmpl.id;
  }

  async deleteTemplate(id: string): Promise<void> {
    if (!this.mock) {
      try {
        await this.api.deleteProfileTemplate(id);
      } catch (e: any) {
        this.toast(`delete template failed: ${e.message}`);
        return;
      }
    }
    this.profileTemplates.update(ts => ts.filter(t => t.id !== id));
  }

  /** Deploy a template into a container as a new agent. Returns the agent id, or ''. */
  async deployTemplate(templateId: string, containerId: string, name: string): Promise<string> {
    const t = this.templateById(templateId);
    if (!t) return '';
    if (!this.mock) {
      const container = this.containers().find(c => c.id === containerId);
      if (!container) return '';
      try {
        const created = await this.api.deployTemplate(templateId, container.hostId, containerId, name);
        const agent = this.toAgentProfile(created);
        this.agents.update(as => [...as.filter(a => a.id !== agent.id), agent]);
        return agent.id;
      } catch (e: any) {
        this.toast(`deploy template failed: ${e.message}`);
        return '';
      }
    }
    return this.createAgent(containerId, name, t.provider || 'anthropic', t.model || 'claude-fable-5', '', undefined, t.baseUrl || undefined, templateId);
  }

  /** Snapshot a running agent's config into a new template. Returns the template id. */
  async captureTemplate(agentId: string, templateName?: string): Promise<string> {
    const a = this.agentById(agentId);
    if (!a) return '';
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return '';
      try {
        const t = await this.api.captureTemplate(container.hostId, a.containerId, a.name, templateName);
        this.upsertTemplate(this.toTemplate(t));
        return t.id;
      } catch (e: any) {
        this.toast(`capture template failed: ${e.message}`);
        return '';
      }
    }
    const now = Date.now();
    // mirror the backend capture: raw .env values can't be read back, so we record
    // which provider key was set (by its real env var, not a hardcoded one) as an
    // unset placeholder the user re-enters before deploy.
    const keyVar = this.llmProviders().find(p => p.key === a.provider)?.envVar;
    const tmpl: ProfileTemplate = {
      id: nid('pt'), name: templateName?.trim() || `${a.name}-template`,
      description: `Captured from ${a.name}`, provider: a.provider, model: a.model,
      baseUrl: '', cwd: a.cwd, soul: a.soul, memory: a.memoryMd,
      skills: a.skills.filter(s => s.enabled).map(s => s.name),
      mcpServers: a.mcp.map(m => ({
        name: m.name, transport: m.transport, url: m.url, command: m.command, args: m.args,
        enabled: m.status !== 'disabled',
      })),
      secrets: keyVar ? [{ key: keyVar, set: false, recoverable: false }] : [],
      createdAt: now, updatedAt: now,
    };
    this.upsertTemplate(tmpl);
    return tmpl.id;
  }
}
