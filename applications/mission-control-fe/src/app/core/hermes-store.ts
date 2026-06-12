import { Injectable, computed, signal } from '@angular/core';
import {
  AgentProfile, BoardColumn, BoardTask, ContainerStatus, CronJob, DockerHost,
  HermesContainer, Integration, LogEntry, McpServer, SkillRef, Webhook,
} from './models';
import {
  seedAgents, seedContainers, seedDockerHosts, seedJobs, seedLogs, seedTasks, seedWebhooks,
} from './mock-data';
import { runtimeConfig } from './app-config';
import { HermesApi } from './hermes-api';

let uid = 0;
const nid = (p: string) => `${p}-${Date.now().toString(36)}-${uid++}`;

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

  readonly dockerHosts = signal<DockerHost[]>(
    this.mock
      ? seedDockerHosts(this.config.dockerSocket)
      : [{
          id: 'dh-local', name: 'localhost', url: this.config.dockerSocket, kind: 'local',
          status: 'disconnected', engine: null, apiVersion: null, latencyMs: null,
          note: 'waiting for backend connection',
        }]);
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
    await Promise.all([this.refreshHosts(), this.refreshContainers(), this.refreshBoard()]);
    setInterval(() => this.refreshContainers(), 10_000);
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

  // ── container actions ──────────────────────────────────────────────────
  selectContainer(id: string): void {
    this.selectedContainerId.set(id);
  }

  /** Returns the new container id in mock mode; null in live mode (the
   *  inventory refresh selects it once the daemon reports it). */
  deployContainer(name: string, version: string, profileNames: string[], hostId = 'dh-local'): string | null {
    if (!this.mock) {
      this.api.deploy(hostId, name, version.replace(/^v/, ''), profileNames)
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
      this.createAgent(id, p, 'anthropic', 'claude-fable-5', 'sk-ant-…new');
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

  // ── agent actions (mock only — hermes profile introspection is roadmap) ─
  createAgent(containerId: string, name: string, provider: string, model: string, apiKeyMasked: string, cloneFromId?: string): string {
    if (!this.mock) {
      this.toast('agent profiles require the hermes adapter — not available in live mode yet');
      return '';
    }
    const id = nid('a');
    const src = cloneFromId ? this.agentById(cloneFromId) : null;
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
    this.agents.update(as => as.filter(a => a.id !== id));
    this.jobs.update(js => js.filter(j => j.agentId !== id));
    this.tasks.update(ts => ts.filter(t => t.agentId !== id));
    this.webhooks.update(ws => ws.filter(w => w.agentId !== id));
    if (agent) {
      this.appendLog(agent.containerId, {
        ts: Date.now(), level: 'info', source: 'system', agentId: null,
        msg: `profile "${agent.name}" deleted`,
      });
    }
  }

  private patchAgent(id: string, patch: Partial<AgentProfile>): void {
    this.agents.update(as => as.map(a => a.id === id ? { ...a, ...patch } : a));
  }

  updateSoul(id: string, soul: string): void {
    this.patchAgent(id, { soul });
    const a = this.agentById(id);
    if (a) this.appendLog(a.containerId, { ts: Date.now(), level: 'info', source: 'system', agentId: id, msg: 'SOUL.md updated via dashboard' });
  }

  toggleSkill(agentId: string, skillId: string): void {
    this.agents.update(as => as.map(a => a.id !== agentId ? a : {
      ...a, skills: a.skills.map(s => s.id === skillId ? { ...s, enabled: !s.enabled } : s),
    }));
  }

  addSkill(agentId: string, skill: Omit<SkillRef, 'id'>): void {
    this.agents.update(as => as.map(a => a.id !== agentId ? a : {
      ...a, skills: [...a.skills, { ...skill, id: nid('s') }],
    }));
  }

  removeSkill(agentId: string, skillId: string): void {
    this.agents.update(as => as.map(a => a.id !== agentId ? a : {
      ...a, skills: a.skills.filter(s => s.id !== skillId),
    }));
  }

  addMcp(agentId: string, name: string, transport: McpServer['transport']): void {
    const server: McpServer = {
      id: nid('m'), name, transport, status: 'connected',
      tools: 3 + Math.floor(Math.random() * 20), latencyMs: 30 + Math.floor(Math.random() * 200),
    };
    this.agents.update(as => as.map(a => a.id !== agentId ? a : { ...a, mcp: [...a.mcp, server] }));
  }

  removeMcp(agentId: string, mcpId: string): void {
    this.agents.update(as => as.map(a => a.id !== agentId ? a : {
      ...a, mcp: a.mcp.filter(m => m.id !== mcpId),
    }));
  }

  /** Simulated connectivity check — resolves each integration after a beat. */
  pingIntegrations(agentId: string): void {
    const a = this.agentById(agentId);
    if (!a) return;
    setTimeout(() => {
      this.agents.update(as => as.map(x => x.id !== agentId ? x : {
        ...x,
        integrations: x.integrations.map<Integration>(i =>
          i.status === 'off' ? i : { ...i, status: Math.random() < 0.9 ? 'up' : 'degraded' }),
      }));
    }, 900);
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
