import { Injectable, computed, signal } from '@angular/core';
import {
  AgentProfile, BoardColumn, BoardTask, ContainerStatus, CronJob, DockerHost,
  HermesContainer, Integration, LogEntry, McpServer, ModelProvider, OllamaModel, SkillRef, Webhook,
} from './models';
import {
  seedAgents, seedContainers, seedDockerHosts, seedJobs, seedLogs, seedTasks, seedWebhooks,
} from './mock-data';
import { runtimeConfig } from './app-config';
import { ApiAgentProfile, ApiAgentSetup, ApiImageTags, ApiPullState, HermesApi } from './hermes-api';

let uid = 0;
const nid = (p: string) => `${p}-${Date.now().toString(36)}-${uid++}`;

/** Known model ids per cloud provider — used when the backend (or the
 *  provider API) cannot be reached. */
const FALLBACK_MODELS: Record<string, string[]> = {
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

  /** Bumped by pages that want the bottom terminal panel opened. */
  readonly terminalRequest = signal(0);

  openTerminal(): void {
    this.terminalRequest.update(n => n + 1);
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
  readonly containers = signal<HermesContainer[]>(this.mock ? seedContainers() : []);
  readonly agents = signal<AgentProfile[]>(this.mock ? seedAgents() : []);
  readonly jobs = signal<CronJob[]>(this.mock ? seedJobs() : []);
  readonly tasks = signal<BoardTask[]>(this.mock ? seedTasks() : []);
  readonly webhooks = signal<Webhook[]>(this.mock ? seedWebhooks() : []);
  private readonly logsByContainer = signal<Record<string, LogEntry[]>>(this.mock ? seedLogs() : {});

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
        status: m.status as any,
        tools: m.tools,
        latencyMs: m.latencyMs,
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
    await Promise.all([this.refreshHosts(), this.refreshModelProviders(), this.refreshContainers(), this.refreshBoard()]);
    await this.refreshAgents();   // needs the container list
    setInterval(() => this.refreshContainers(), 10_000);
    setInterval(() => this.refreshAgents(), 12_000);
    setInterval(() => this.pollStats(), 3_000);
    setInterval(() => this.pollLogs(), 5_000);
    this.pollStats();
    this.pollLogs();
  }

  private toast(message: string): void {
    this.liveError.set(message);
    setTimeout(() => this.liveError.set(null), 6_000);
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
      if (!this.selectedContainerId() && list.length) {
        this.selectedContainerId.set(list[0].id);
      }
    } catch { /* keep last inventory */ }
  }

  private async refreshAgents(): Promise<void> {
    const containers = this.containers();
    if (!containers.length) {
      this.agents.set([]);
      return;
    }
    const prev = this.agents();
    const lists = await Promise.all(containers.map(c =>
      this.api.agents(c.hostId, c.id)
        .then(list => list.map(a => this.toAgentProfile(a)))
        // transient per-container failure — keep its last known profiles
        .catch(() => prev.filter(a => a.containerId === c.id))));
    this.agents.set(lists.flat());
  }

  private async pollStats(): Promise<void> {
    const running = this.containers().filter(c => c.status === 'running' || c.status === 'unhealthy');
    await Promise.all(running.map(async c => {
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
    }));
  }

  private async pollLogs(): Promise<void> {
    const c = this.selectedContainer();
    if (!c || c.status === 'stopped') return;
    try {
      const lines = await this.api.logs(c.hostId, c.id, 100);
      this.logsByContainer.update(m => ({
        ...m,
        [c.id]: lines.map(l => ({ ...l, agentId: null })),
      }));
    } catch { /* tail is best-effort */ }
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

  // ── image tags ───────────────────────────────────────────────────────

  imageTags(hostId: string): Promise<ApiImageTags> {
    if (this.mock) {
      const repo = this.containers()[0]?.image ?? 'nousresearch/hermes-agent';
      const tags = Array.from(new Set(this.containers().map(c => c.version).filter(Boolean)));
      tags.sort((a, b) => b.localeCompare(a));
      return Promise.resolve({ repository: repo, tags });
    }
    return this.api.imageTags(hostId);
  }

  // ── model provider actions (ollama registry) ───────────────────────────
  async refreshModelProviders(): Promise<void> {
    try {
      this.modelProviders.set(await this.api.modelProviders());
    } catch { /* transient backend hiccup — keep last known state */ }
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
  }

  /** Returns the new container id in mock mode; null in live mode (the
   *  inventory refresh selects it once the daemon reports it). */
  deployContainer(name: string, version: string, profileNames: string[], hostId = 'dh-local'): string | null {
    if (!this.mock) {
      this.api.deploy(hostId, name, version, profileNames)
        .then(r => setTimeout(async () => {
          await this.refreshContainers();
          this.selectedContainerId.set(r.id);
        }, 600))
        .catch(e => this.toast(`deploy failed: ${e.message}`));
      return null;
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

  removeContainer(id: string): void {
    if (!this.mock) {
      const container = this.containers().find(c => c.id === id);
      if (!container) return;
      this.api.removeContainer(container.hostId, id)
        .then(() => {
          if (this.selectedContainerId() === id) this.selectedContainerId.set('');
          return this.refreshContainers();
        })
        .catch(e => this.toast(`remove failed: ${e.message}`));
      return;
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
    const apiKeyMasked = apiKey ? `…${apiKey.slice(-4)}` : '…';
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
    this.agents.update(as => [...as, agent]);
    this.appendLog(containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: id,
      msg: `profile "${name}" created${src ? ` (cloned from ${src.name})` : ''}`,
    });
    return id;
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

  updateSoul(id: string, soul: string): void {
    const a = this.agentById(id);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return;
      this.api.updateSoul(container.hostId, a.containerId, a.name, soul)
        .then(() => this.patchAgent(id, { soul }))
        .catch(e => this.toast(`SOUL.md save failed: ${e.message}`));
      return;
    }
    this.patchAgent(id, { soul });
    this.appendLog(a.containerId, { ts: Date.now(), level: 'info', source: 'system', agentId: id, msg: 'SOUL.md updated via dashboard' });
  }

  updateAgentConfig(agentId: string, configYaml: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return;
      this.applyAgentCall(agentId, 'config save',
        this.api.updateAgentConfig(container.hostId, a.containerId, a.name, configYaml));
      return;
    }
    this.patchAgent(agentId, { configYaml });
    this.appendLog(a.containerId, { ts: Date.now(), level: 'info', source: 'system', agentId, msg: 'config.yaml updated via dashboard' });
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

  addMcp(
    agentId: string,
    name: string,
    transport: McpServer['transport'],
    opts?: { url?: string; command?: string; args?: string },
  ): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      if (!container) return;
      this.applyAgentCall(agentId, 'mcp add',
        this.api.addMcpServer(container.hostId, a.containerId, a.name, {
          name,
          transport,
          url: opts?.url,
          command: opts?.command,
          args: opts?.args,
        }));
      return;
    }
    const server: McpServer = {
      id: nid('m'), name, transport, status: 'connected',
      tools: 3 + Math.floor(Math.random() * 20), latencyMs: 30 + Math.floor(Math.random() * 200),
    };
    this.agents.update(as => as.map(x => x.id !== agentId ? x : { ...x, mcp: [...x.mcp, server] }));
  }

  removeMcp(agentId: string, mcpId: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    if (!this.mock) {
      const container = this.containers().find(c => c.id === a.containerId);
      const server = a.mcp.find(m => m.id === mcpId);
      if (!container || !server) return;
      this.applyAgentCall(agentId, 'mcp remove',
        this.api.removeMcpServer(container.hostId, a.containerId, a.name, server.name));
      return;
    }
    this.agents.update(as => as.map(x => x.id !== agentId ? x : {
      ...x, mcp: x.mcp.filter(m => m.id !== mcpId),
    }));
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
    const mask = (v: string | undefined) => v ? '...' + v.slice(-4) : null;
    return {
      envPath: `/opt/data/profiles/${agent.name}/.env`,
      envExists: this.mockEnv.has(agent.id),
      apiKeys: MOCK_SETUP_API_KEYS.map(([label, envVar]) => ({
        label, envVar, set: !!env[envVar], masked: mask(env[envVar]),
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
}
