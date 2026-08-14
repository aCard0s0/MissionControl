import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from './hermes-store';

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
    (store as any).mock = false;
    (store as any).api = { updateSoul: vi.fn().mockRejectedValue(new Error('offline')) };

    expect(await store.updateSoul(agent.id, 'must not land')).toBe(false);
    expect(store.agentById(agent.id)?.soul).toBe(original);
  });

  it('reports failed container deletion without optimistically dropping inventory', async () => {
    const store = new HermesStore();
    const container = store.containers()[0];
    (store as any).mock = false;
    (store as any).api = {
      removeContainer: vi.fn().mockRejectedValue(new Error('volume busy')),
      containers: vi.fn().mockRejectedValue(new Error('offline')),
    };

    expect(await store.removeContainer(container.id)).toBe(false);
    expect(store.containers().some(c => c.id === container.id)).toBe(true);
  });

  it('fetches logs immediately on selection and prevents overlapping polls', async () => {
    const store = new HermesStore();
    const container = store.containers()[0];
    let resolveLogs!: (value: any[]) => void;
    const pending = new Promise<any[]>(resolve => { resolveLogs = resolve; });
    const logs = vi.fn().mockReturnValue(pending);
    (store as any).mock = false;
    (store as any).api = { logs };
    (store as any).logsByContainer.set({});

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
    (store as any).mock = false;
    (store as any).api = {
      testMcpServer: vi.fn().mockResolvedValue({
        name: server.name, status: 'error', tools: 0, latencyMs: null,
        error: 'Connection failed', checkedAt: 456,
      }),
    };

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
    (store as any).mock = false;
    (store as any).api = { agentLogs };

    const lines = await store.agentLogTail(agent.id, 25);

    expect(agentLogs).toHaveBeenCalledWith(container.hostId, container.id, agent.name, 25);
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
    (store as any).mock = false;
    (store as any).api = {
      updateContainer: vi.fn().mockResolvedValue({ id: 'c-updated' }),
      containers: vi.fn().mockResolvedValue([{
        id: 'c-updated', shortId: 'aa11bb2', name: target.name, hostId: target.hostId,
        status: 'running', image: target.image, version: 'v2026.8.3',
        startedAt: 1, sizeRootFsGb: 1, profiles: [],
      }]),
      logs: vi.fn().mockResolvedValue([]),        // selectContainer polls logs in live mode
      imageTags: vi.fn().mockResolvedValue({ repository: target.image, tags: [] }),
    };

    expect(await store.updateContainer(target.id, 'v2026.8.3')).toBe('c-updated');
    expect((store as any).api.updateContainer)
      .toHaveBeenCalledWith(target.hostId, target.id, 'v2026.8.3');
    expect(store.selectedContainerId()).toBe('c-updated');
    expect(store.selectedContainer()?.version).toBe('v2026.8.3');
  });

  it('reports a failed update and keeps the container it could not replace', async () => {
    const store = new HermesStore();
    const target = store.containers()[0];
    store.selectContainer(target.id);
    (store as any).mock = false;
    (store as any).api = {
      updateContainer: vi.fn().mockRejectedValue(new Error('image pull timed out')),
      containers: vi.fn().mockRejectedValue(new Error('offline')),
    };

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
    (store as any).mock = false;
    (store as any).api = { imageTags };

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
    (store as any).mock = false;
    (store as any).api = {
      imageTags: vi.fn().mockResolvedValue({
        repository: 'nousresearch/hermes-agent', tags: ['v2026.7.20'],
      }),
    };

    await store.refreshImageCatalog('dh-local');
    expect(store.imageCatalog()['dh-local'].tags).toEqual([{ tag: 'v2026.7.20', pulled: true }]);
  });
});
