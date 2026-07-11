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

  it('preserves named volumes as retained data when a managed server is removed', async () => {
    const store = new HermesStore();
    const postgres = store.mcpServers().find(server => server.id === 'mcp-postgres')!;

    expect(await store.deleteCatalogMcpServer(postgres.id)).toBe(true);
    expect(store.mcpServerById(postgres.id)).toBeNull();
    expect(store.retainedMcpResources()).toEqual(expect.arrayContaining([
      expect.objectContaining({ serverId: postgres.id, type: 'volume', name: 'postgres-data' }),
    ]));
  });
});
