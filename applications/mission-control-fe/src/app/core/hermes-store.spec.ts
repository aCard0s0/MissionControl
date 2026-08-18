import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from './hermes-store';

/**
 * Substitutes the backend for a whole constructed store: `api` lives on the
 * shared StoreContext, so one call switches every slice at once. It is shaped
 * like {@link HermesApi}, with only the calls a test reaches.
 */
const stubBackend = (store: HermesStore, api: unknown): void => {
  (store as any).ctx.api = api;
};

const CONTAINER = {
  id: 'c-1', shortId: 'aa11bb2', name: 'hermes-prod', hostId: 'dh-local', status: 'running',
  image: 'nousresearch/hermes-agent', version: 'v2026.8.3', startedAt: 10, sizeRootFsGb: 2,
  profiles: ['atlas'],
};

const PROFILE = {
  id: 'a-1', containerId: 'c-1', name: 'atlas', role: 'Ops', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…9f2c', cwd: '/opt/data',
  soul: '# SOUL', memoryMd: '# MEMORY', configYaml: 'provider: anthropic',
  skills: [], mcp: [{ id: 'm1', name: 'github', transport: 'http', status: 'connected', tools: 4, latencyMs: 30 }],
  integrations: [], lastActive: 20,
};

/**
 * A store holding one container and one profile, loaded the way the pollers load
 * them. The slices are reached directly because loading is {@link LiveSync}'s
 * job, and these tests are about what happens after the data is there.
 */
const loaded = async (api: { containers?: any; agents?: any } = {}) => {
  const store = new HermesStore();
  stubBackend(store, {
    ...api,
    containers: { list: vi.fn().mockResolvedValue([CONTAINER]), ...api.containers },
    agents: { list: vi.fn().mockResolvedValue([PROFILE]), ...api.agents },
  });
  await (store as any).containerStore.refresh();
  await (store as any).agentStore.refresh();
  return store;
};

describe('HermesStore mutation results', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = { apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('updates SOUL only after a successful save', async () => {
    const store = await loaded({ agents: { updateSoul: vi.fn().mockResolvedValue(undefined) } });

    expect(await store.updateSoul('a-1', 'new soul')).toBe(true);
    expect(store.agentById('a-1')?.soul).toBe('new soul');
  });

  it('returns false and retains data when a save fails', async () => {
    const store = await loaded({ agents: { updateSoul: vi.fn().mockRejectedValue(new Error('offline')) } });

    expect(await store.updateSoul('a-1', 'must not land')).toBe(false);
    expect(store.agentById('a-1')?.soul).toBe('# SOUL');
    expect(store.liveError()).toBe('SOUL.md save failed: offline');
  });

  it('reports failed container deletion without optimistically dropping inventory', async () => {
    const store = await loaded({
      containers: {
        list: vi.fn().mockResolvedValue([CONTAINER]),
        remove: vi.fn().mockRejectedValue(new Error('volume busy')),
      },
    });

    expect(await store.removeContainer('c-1')).toBe(false);
    expect(store.containers().some(c => c.id === 'c-1')).toBe(true);
  });

  it('fetches logs immediately on selection and prevents overlapping polls', async () => {
    let resolveLogs!: (value: any[]) => void;
    const pending = new Promise<any[]>(resolve => { resolveLogs = resolve; });
    const logs = vi.fn().mockReturnValue(pending);
    const store = await loaded({ containers: { list: vi.fn().mockResolvedValue([CONTAINER]), logs } });

    store.selectContainer('c-1');
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

  it('clears the log view when the operator switches containers', async () => {
    const store = await loaded({
      containers: {
        list: vi.fn().mockResolvedValue([CONTAINER, { ...CONTAINER, id: 'c-2', name: 'hermes-lab' }]),
        logs: vi.fn().mockResolvedValue([{ ts: 1, level: 'info', source: 'container', msg: 'first' }]),
      },
    });
    store.selectContainer('c-1');
    await Promise.resolve();
    await Promise.resolve();

    store.selectContainer('c-2');

    // the previous container's tail must not read as this one's
    expect(store.logsUpdatedAt()).toBeNull();
    expect(store.logsError()).toBeNull();
  });

  it('keeps MCP failures after a real probe result', async () => {
    const store = await loaded({
      agents: {
        list: vi.fn().mockResolvedValue([PROFILE]),
        mcp: {
          test: vi.fn().mockResolvedValue({
            name: 'github', status: 'error', tools: 0, latencyMs: null,
            error: 'Connection failed', checkedAt: 456,
          }),
        },
      },
    });

    expect(await store.testMcp('a-1', 'github')).toBe(false);
    expect(store.agentById('a-1')?.mcp[0]).toMatchObject({
      status: 'error', error: 'Connection failed', checkedAt: 456,
    });
  });

  it('loads a profile-scoped gateway tail and orders it newest first', async () => {
    const agentLogs = vi.fn().mockResolvedValue([
      { ts: 100, level: 'info', source: 'atlas', msg: 'older' },
      { ts: 200, level: 'warn', source: 'atlas', msg: 'newer' },
    ]);
    const store = await loaded({
      agents: { list: vi.fn().mockResolvedValue([PROFILE]), logs: agentLogs },
    });

    const lines = await store.agentLogTail('a-1', 25);

    expect(agentLogs).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' }, 25);
    expect(lines.map(line => line.msg)).toEqual(['newer', 'older']);
    expect(lines.every(line => line.agentId === 'a-1')).toBe(true);
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
});

describe('HermesStore container updates', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    window.__MC_CONFIG__ = { apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('follows the selection onto the id a container update mints', async () => {
    const update = vi.fn().mockResolvedValue({ id: 'c-updated' });
    const store = await loaded({
      containers: {
        update,
        list: vi.fn()
          .mockResolvedValueOnce([CONTAINER])
          .mockResolvedValue([{ ...CONTAINER, id: 'c-updated', version: 'v2026.9.1' }]),
        logs: vi.fn().mockResolvedValue([]),      // selecting a container polls its logs
        imageTags: vi.fn().mockResolvedValue({ repository: CONTAINER.image, tags: [] }),
      },
    });
    store.selectContainer('c-1');

    expect(await store.updateContainer('c-1', 'v2026.9.1')).toBe('c-updated');
    expect(update).toHaveBeenCalledWith('dh-local', 'c-1', 'v2026.9.1');
    expect(store.selectedContainerId()).toBe('c-updated');
    expect(store.selectedContainer()?.version).toBe('v2026.9.1');
  });

  it('reports a failed update and keeps the container it could not replace', async () => {
    const store = await loaded({
      containers: {
        list: vi.fn().mockResolvedValueOnce([CONTAINER]).mockRejectedValue(new Error('offline')),
        update: vi.fn().mockRejectedValue(new Error('image pull timed out')),
      },
    });
    store.selectContainer('c-1');

    expect(await store.updateContainer('c-1', 'v2026.9.1')).toBe('');
    expect(store.containers().some(c => c.id === 'c-1')).toBe(true);
    expect(store.selectedContainerId()).toBe('c-1');
    expect(store.liveError()).toBe('update failed: image pull timed out');
  });

  it('refuses to update a container onto the tag it already runs', async () => {
    const update = vi.fn();
    const store = await loaded({
      containers: { list: vi.fn().mockResolvedValue([CONTAINER]), update },
    });

    expect(await store.updateContainer('c-1', 'v2026.8.3')).toBe('');
    expect(update).not.toHaveBeenCalled();
    expect(store.containers().some(c => c.id === 'c-1')).toBe(true);
  });

  it('caches the image catalog per host and refetches only when forced', async () => {
    const imageTags = vi.fn().mockResolvedValue({
      repository: 'nousresearch/hermes-agent',
      tags: ['v2026.8.3', 'v2026.7.20'],
      entries: [{ tag: 'v2026.8.3', pulled: false }, { tag: 'v2026.7.20', pulled: true }],
      registryStatus: 'ok',
    });
    const store = await loaded({
      containers: { list: vi.fn().mockResolvedValue([CONTAINER]), imageTags },
    });

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
    const store = await loaded({
      containers: {
        list: vi.fn().mockResolvedValue([CONTAINER]),
        imageTags: vi.fn().mockResolvedValue({
          repository: 'nousresearch/hermes-agent', tags: ['v2026.7.20'],
        }),
      },
    });

    await store.refreshImageCatalog('dh-local');
    expect(store.imageCatalog()['dh-local'].tags).toEqual([{ tag: 'v2026.7.20', pulled: true }]);
  });
});
