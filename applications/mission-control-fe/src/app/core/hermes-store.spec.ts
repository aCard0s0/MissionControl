import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from './hermes-store';

/**
 * Flips a constructed store onto live mode against a stubbed backend. Both live
 * there on the shared StoreContext, so one call switches every slice at once —
 * `api` is shaped like {@link HermesApi}, with only the calls a test reaches.
 */
const goLive = (store: HermesStore, api: unknown): void => {
  const ctx = (store as any).ctx;
  ctx.mock = false;
  ctx.api = api;
};

describe('HermesStore mutation results', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      dataMode: 'mock', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('updates SOUL only after a successful save', async () => {
    const store = new HermesStore();
    const agent = store.agents()[0];

    expect(await store.updateSoul(agent.id, 'new soul')).toBe(true);
    expect(store.agentById(agent.id)?.soul).toBe('new soul');
  });

  it('returns false and retains data when a live save fails', async () => {
    const store = new HermesStore();
    const agent = store.agents()[0];
    const original = agent.soul;
    goLive(store, { agents: { updateSoul: vi.fn().mockRejectedValue(new Error('offline')) } });

    expect(await store.updateSoul(agent.id, 'must not land')).toBe(false);
    expect(store.agentById(agent.id)?.soul).toBe(original);
  });

  it('reports failed container deletion without optimistically dropping inventory', async () => {
    const store = new HermesStore();
    const container = store.containers()[0];
    goLive(store, {
      containers: {
        remove: vi.fn().mockRejectedValue(new Error('volume busy')),
        list: vi.fn().mockRejectedValue(new Error('offline')),
      },
    });

    expect(await store.removeContainer(container.id)).toBe(false);
    expect(store.containers().some(c => c.id === container.id)).toBe(true);
  });

  it('fetches logs immediately on selection and prevents overlapping polls', async () => {
    const store = new HermesStore();
    const container = store.containers()[0];
    let resolveLogs!: (value: any[]) => void;
    const pending = new Promise<any[]>(resolve => { resolveLogs = resolve; });
    const logs = vi.fn().mockReturnValue(pending);
    goLive(store, { containers: { logs } });
    (store as any).logStore.byContainer.set({});

    store.selectContainer(container.id);
    store.refreshLogs();

    expect(logs).toHaveBeenCalledTimes(1);
    expect(store.logsLoading()).toBe(true);
    resolveLogs([{ ts: 123, level: 'warn', source: 'container', msg: 'warning' }]);
    await pending;
    await Promise.resolve();
    expect(store.containerLogs()[0]?.msg).toBe('warning');
    expect(store.logsLoading()).toBe(false);
    expect(store.logsUpdatedAt()).not.toBeNull();
  });

  it('keeps MCP failures after a real probe result', async () => {
    const store = new HermesStore();
    const agent = store.agents().find(a => a.mcp.length > 0)!;
    const server = agent.mcp[0];
    goLive(store, {
      agents: {
        mcp: {
          test: vi.fn().mockResolvedValue({
            name: server.name, status: 'error', tools: 0, latencyMs: null,
            error: 'Connection failed', checkedAt: 456,
          }),
        },
      },
    });

    expect(await store.testMcp(agent.id, server.name)).toBe(false);
    expect(store.agentById(agent.id)?.mcp[0]).toMatchObject({
      status: 'error', error: 'Connection failed', checkedAt: 456,
    });
  });

  it('loads a profile-scoped gateway tail and orders it newest first', async () => {
    const store = new HermesStore();
    const agent = store.agents()[0];
    const container = store.containers().find(c => c.id === agent.containerId)!;
    const agentLogs = vi.fn().mockResolvedValue([
      { ts: 100, level: 'info', source: agent.name, msg: 'older' },
      { ts: 200, level: 'warn', source: agent.name, msg: 'newer' },
    ]);
    goLive(store, { agents: { logs: agentLogs } });

    const lines = await store.agentLogTail(agent.id, 25);

    expect(agentLogs).toHaveBeenCalledWith(
      { hostId: container.hostId, containerId: container.id, name: agent.name }, 25);
    expect(lines.map(line => line.msg)).toEqual(['newer', 'older']);
    expect(lines.every(line => line.agentId === agent.id)).toBe(true);
  });

  it('disconnects and reconnects MCP entries without forgetting their configuration', async () => {
    const store = new HermesStore();
    const agent = store.agents().find(item => item.mcp.length)!;
    const server = agent.mcp[0];

    expect(await store.setMcpEnabled(agent.id, server.name, false)).toBe(true);
    expect(store.agentById(agent.id)?.mcp.find(item => item.id === server.id)).toMatchObject({
      enabled: false, status: 'disabled', url: server.url,
    });

    expect(await store.setMcpEnabled(agent.id, server.name, true)).toBe(true);
    expect(store.agentById(agent.id)?.mcp.find(item => item.id === server.id)).toMatchObject({
      enabled: true, status: 'unknown', url: server.url,
    });
  });

  it('starts and connects a managed catalog server without overwriting an alias', async () => {
    const store = new HermesStore();
    const agent = store.agents()[0];
    const catalog = store.mcpServers().find(server => server.kind === 'managed')!;

    expect(await store.connectCatalogMcp(agent.id, catalog.id, 'browser-tools')).toBe(true);
    expect(store.mcpServerById(catalog.id)?.runtimeState).toBe('running');
    expect(store.agentById(agent.id)?.mcp.find(server => server.name === 'browser-tools')).toMatchObject({
      enabled: true, origin: 'catalog', catalogServerId: catalog.id,
      syncedRevision: catalog.revision, updateAvailable: false,
    });

    expect(await store.connectCatalogMcp(agent.id, catalog.id, 'browser-tools')).toBe(false);
    expect(store.agentById(agent.id)?.mcp.filter(server => server.name === 'browser-tools')).toHaveLength(1);
  });

  it('opens the terminal with no target, as the setup hints have always done', () => {
    const store = new HermesStore();

    expect(store.terminalRequest()).toBeNull();
    store.openTerminal();
    expect(store.terminalRequest()).toEqual({ seq: 1 });
  });

  it('carries an agent target through to the panel, with a fresh seq each click', () => {
    const store = new HermesStore();
    const target = {
      hostId: 'dh-local', containerId: 'c-prod', label: 'ops-bot',
      agentKey: 'c-prod--ops-bot', command: 'hermes -p ops-bot',
    };

    store.openTerminal(target);
    expect(store.terminalRequest()).toEqual({ ...target, seq: 1 });

    // a repeat click on the same agent must still read as a new request, or the
    // panel's seq guard would swallow it and never focus the tab
    store.openTerminal(target);
    expect(store.terminalRequest()).toEqual({ ...target, seq: 2 });
  });

  it('preserves named volumes as retained data when a managed server is removed', async () => {
    const store = new HermesStore();
    const postgres = store.mcpServers().find(server => server.id === 'mcp-postgres')!;

    expect(await store.deleteCatalogMcpServer(postgres.id)).toBe(true);
    expect(store.mcpServerById(postgres.id)).toBeNull();
    expect(store.retainedMcpResources()).toEqual(expect.arrayContaining([
      expect.objectContaining({ serverId: postgres.id, type: 'volume', name: 'postgres-data' }),
    ]));
  });

  it('follows the selection onto the id a container update mints', async () => {
    const store = new HermesStore();
    const target = store.containers()[0];
    store.selectContainer(target.id);
    const update = vi.fn().mockResolvedValue({ id: 'c-updated' });
    goLive(store, {
      containers: {
        update,
        list: vi.fn().mockResolvedValue([{
          id: 'c-updated', shortId: 'aa11bb2', name: target.name, hostId: target.hostId,
          status: 'running', image: target.image, version: 'v2026.8.3',
          startedAt: 1, sizeRootFsGb: 1, profiles: [],
        }]),
        logs: vi.fn().mockResolvedValue([]),      // selectContainer polls logs in live mode
        imageTags: vi.fn().mockResolvedValue({ repository: target.image, tags: [] }),
      },
    });

    expect(await store.updateContainer(target.id, 'v2026.8.3')).toBe('c-updated');
    expect(update).toHaveBeenCalledWith(target.hostId, target.id, 'v2026.8.3');
    expect(store.selectedContainerId()).toBe('c-updated');
    expect(store.selectedContainer()?.version).toBe('v2026.8.3');
  });

  it('reports a failed update and keeps the container it could not replace', async () => {
    const store = new HermesStore();
    const target = store.containers()[0];
    store.selectContainer(target.id);
    goLive(store, {
      containers: {
        update: vi.fn().mockRejectedValue(new Error('image pull timed out')),
        list: vi.fn().mockRejectedValue(new Error('offline')),
      },
    });

    expect(await store.updateContainer(target.id, 'v2026.8.3')).toBe('');
    expect(store.containers().some(c => c.id === target.id)).toBe(true);
    expect(store.selectedContainerId()).toBe(target.id);
    expect(store.liveError()).toBe('update failed: image pull timed out');
  });

  it('carries profiles and the selection across the id change in mock mode', async () => {
    const store = new HermesStore();
    const target = store.containers()[0];
    store.selectContainer(target.id);
    const profiles = store.containerAgents().length;

    const newId = await store.updateContainer(target.id, 'v2026.8.3');

    expect(newId).not.toBe(target.id);
    expect(store.containers()).toHaveLength(3);
    expect(store.containers().find(c => c.id === newId)).toMatchObject({
      name: target.name, version: 'v2026.8.3', status: 'running',
    });
    expect(store.selectedContainerId()).toBe(newId);
    expect(store.containerAgents()).toHaveLength(profiles);
  });

  it('refuses to update a container onto the tag it already runs', async () => {
    const store = new HermesStore();
    const target = store.containers()[0];

    expect(await store.updateContainer(target.id, target.version)).toBe('');
    expect(store.containers().some(c => c.id === target.id)).toBe(true);
  });

  it('caches the image catalog per host and refetches only when forced', async () => {
    const store = new HermesStore();
    const imageTags = vi.fn().mockResolvedValue({
      repository: 'nousresearch/hermes-agent',
      tags: ['v2026.8.3', 'v2026.7.20'],
      entries: [{ tag: 'v2026.8.3', pulled: false }, { tag: 'v2026.7.20', pulled: true }],
      registryStatus: 'ok',
    });
    goLive(store, { containers: { imageTags } });

    await store.refreshImageCatalog('dh-local');
    await store.refreshImageCatalog('dh-local');
    expect(imageTags).toHaveBeenCalledTimes(1);
    expect(store.imageCatalog()['dh-local'].tags).toEqual([
      { tag: 'v2026.8.3', pulled: false }, { tag: 'v2026.7.20', pulled: true },
    ]);

    await store.refreshImageCatalog('dh-local', true);
    expect(imageTags).toHaveBeenCalledTimes(2);
  });

  it('treats a backend without entries as reporting only pulled tags', async () => {
    const store = new HermesStore();
    goLive(store, {
      containers: {
        imageTags: vi.fn().mockResolvedValue({
          repository: 'nousresearch/hermes-agent', tags: ['v2026.7.20'],
        }),
      },
    });

    await store.refreshImageCatalog('dh-local');
    expect(store.imageCatalog()['dh-local'].tags).toEqual([{ tag: 'v2026.7.20', pulled: true }]);
  });
});

// The store is a facade over one slice per subject (./store). These cover the
// wiring between them: a container id or an agent id is a foreign key in four
// other slices, and mock telemetry has to reach the slices that own the data.
describe('HermesStore cross-slice wiring', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = {
      dataMode: 'mock', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('drops every profile, job, task and webhook a removed container owned', async () => {
    const store = new HermesStore();
    store.selectContainer('c-prod');
    const agentIds = new Set(store.containerAgents().map(a => a.id));
    expect(store.containerJobs().length).toBeGreaterThan(0);
    expect(store.containerTasks().length).toBeGreaterThan(0);
    expect(store.containerWebhooks().length).toBeGreaterThan(0);

    expect(await store.removeContainer('c-prod')).toBe(true);

    expect(store.containers().some(c => c.id === 'c-prod')).toBe(false);
    expect(store.agents().some(a => agentIds.has(a.id))).toBe(false);
    // the selection moves to a surviving container, and nothing keyed to the old
    // one is left behind for it to render
    expect(store.selectedContainerId()).not.toBe('c-prod');
    store.selectContainer('c-prod');
    expect(store.containerJobs()).toEqual([]);
    expect(store.containerTasks()).toEqual([]);
    expect(store.containerWebhooks()).toEqual([]);
  });

  it('drops a removed profile\'s jobs, tasks and webhooks with it', () => {
    const store = new HermesStore();
    store.selectContainer('c-prod');
    expect(store.containerJobs().some(j => j.agentId === 'a-atlas')).toBe(true);
    expect(store.containerWebhooks().some(w => w.agentId === 'a-atlas')).toBe(true);

    store.removeAgent('a-atlas');

    expect(store.agentById('a-atlas')).toBeNull();
    expect(store.containerJobs().some(j => j.agentId === 'a-atlas')).toBe(false);
    expect(store.containerTasks().some(t => t.agentId === 'a-atlas')).toBe(false);
    expect(store.containerWebhooks().some(w => w.agentId === 'a-atlas')).toBe(false);
  });

  it('re-keys jobs and tasks onto the id a container update mints', async () => {
    const store = new HermesStore();
    store.selectContainer('c-prod');
    const jobs = store.containerJobs().length;
    const tasks = store.containerTasks().length;

    const newId = await store.updateContainer('c-prod', 'v2026.8.3');

    expect(newId).not.toBe('c-prod');
    expect(store.selectedContainerId()).toBe(newId);
    expect(store.containerJobs()).toHaveLength(jobs);
    expect(store.containerTasks()).toHaveLength(tasks);
    // the log buffer follows too, plus the line announcing the recreate
    expect(store.containerLogs()[0]?.msg).toContain('recreated on v2026.8.3');
  });

  it('deploys a container with the profiles it was asked for', async () => {
    const store = new HermesStore();

    const id = await store.deployContainer('hermes-new', 'v2026.8.3', ['ops', '']);

    expect(store.containers().some(c => c.id === id)).toBe(true);
    expect(store.agents().filter(a => a.containerId === id).map(a => a.name)).toEqual(['ops']);
  });

  it('stops a container by marking its profiles dormant, not by forgetting them', () => {
    const store = new HermesStore();
    store.selectContainer('c-prod');

    store.setContainerStatus('c-prod', 'stopped');

    expect(store.selectedContainer()?.status).toBe('stopped');
    expect(store.containerAgents().length).toBeGreaterThan(0);
    expect(store.containerAgents().every(a => a.state === 'dormant')).toBe(true);
  });

  it('advances simulated telemetry into the container the sparklines read', () => {
    const store = new HermesStore();
    const before = store.containers().find(c => c.id === 'c-prod')!;

    vi.advanceTimersByTime(3_000);   // two 1.5s mock ticks

    const after = store.containers().find(c => c.id === 'c-prod')!;
    // the seeded history is already at the one-minute cap, so it slides rather
    // than grows — the newest sample is the drifted reading
    expect(after.cpuHist).toHaveLength(before.cpuHist.length);
    expect(after.cpuHist.at(-1)).toBe(after.cpu);
    expect(after.cpuHist.slice(0, -2)).toEqual(before.cpuHist.slice(2));
    expect(after.ramHist.at(-1)).toBe(after.ram);
  });

  it('leaves a stopped container out of the simulation', () => {
    const store = new HermesStore();
    const before = store.containers().find(c => c.id === 'c-lab')!;

    vi.advanceTimersByTime(3_000);

    expect(store.containers().find(c => c.id === 'c-lab')).toEqual(before);
  });

  it('seeds a captured template into the profile it deploys', async () => {
    const store = new HermesStore();
    const source = store.agentById('a-atlas')!;

    const templateId = await store.captureTemplate('a-atlas', 'atlas-blueprint');
    const template = store.templateById(templateId)!;
    expect(template.soul).toBe(source.soul);

    const agentId = await store.deployTemplate(templateId, 'c-lab', 'atlas-clone');
    const deployed = store.agentById(agentId)!;

    expect(deployed).toMatchObject({
      containerId: 'c-lab', name: 'atlas-clone', role: 'From atlas-blueprint',
      soul: source.soul, memoryMd: source.memoryMd,
    });
    expect(deployed.skills.map(s => s.name)).toEqual(template.skills);
  });

  it('clears the log view when the operator switches containers', () => {
    const store = new HermesStore();
    store.selectContainer('c-prod');
    expect(store.containerLogs().length).toBeGreaterThan(0);

    store.selectContainer('c-edge');

    expect(store.logsUpdatedAt()).toBeNull();
    expect(store.logsError()).toBeNull();
    expect(store.logsLoading()).toBe(false);
    // each container keeps its own buffer, so the new one is not empty either
    expect(store.containerLogs().every(line => line.msg.length > 0)).toBe(true);
  });
});
