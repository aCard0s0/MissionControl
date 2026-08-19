import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { McpCatalogServer } from '../models';
import { catalogServer } from '../../testing/models';
import { testSlices } from '../../testing/store';

const store = (mcp: Record<string, unknown>) => {
  const { ctx, catalog } = testSlices({ mcp });
  return { ctx, catalog };
};

/** Loaded with `servers`, with the rest of the MCP client stubbed per test. */
const loaded = async (servers: McpCatalogServer[], mcp: Record<string, unknown> = {}) => {
  const built = store({ list: vi.fn().mockResolvedValue(servers), ...mcp });
  await built.catalog.refresh();
  return built;
};

describe('McpCatalogStore reading', () => {
  it('reports loading while a visible refresh runs, and not for a silent one', async () => {
    const { catalog } = store({ list: vi.fn().mockResolvedValue([]) });

    const visible = catalog.refresh();
    expect(catalog.loading()).toBe(true);
    await visible;
    expect(catalog.loading()).toBe(false);

    const silent = catalog.refresh(true);
    expect(catalog.loading()).toBe(false);
    await silent;
  });

  it('keeps the last catalog when a refresh fails, and says why once', async () => {
    const list = vi.fn().mockResolvedValueOnce([catalogServer('browser')])
      .mockRejectedValue(new Error('gateway down'));
    const { ctx, catalog } = await loaded([catalogServer('browser')], { list });

    await catalog.refresh();

    expect(catalog.servers().map(s => s.id)).toEqual(['browser']);
    expect(ctx.liveError()).toBe('MCP server refresh failed: gateway down');
  });

  it('stays quiet when a silent refresh fails — it is the poller, not an action', async () => {
    const { ctx, catalog } = store({ list: vi.fn().mockRejectedValue(new Error('down')) });

    await catalog.refresh(true);

    expect(ctx.liveError()).toBeNull();
  });

  it('answers byId, and null for an id the catalog does not hold', async () => {
    const { catalog } = await loaded([catalogServer('browser')]);

    expect(catalog.byId('browser')?.name).toBe('browser');
    expect(catalog.byId('missing')).toBeNull();
    expect(catalog.byId(null)).toBeNull();
  });

  it('treats the retained-resource inventory as optional', async () => {
    const { catalog } = await loaded([], {
      retainedResources: vi.fn().mockRejectedValue(new Error('unsupported')),
    });

    await catalog.refreshRetainedResources();

    expect(catalog.retainedResources()).toEqual([]);
  });
});

describe('McpCatalogStore saving', () => {
  it('refuses a name another entry already uses, whatever its case', async () => {
    const create = vi.fn();
    const { ctx, catalog } = await loaded([catalogServer('browser')], { create });

    expect(await catalog.save({ name: 'BROWSER' } as never)).toBe('');
    expect(create).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBe('MCP server name already exists: browser');
  });

  it('allows an entry to keep its own name while being edited', async () => {
    const update = vi.fn().mockResolvedValue(catalogServer('browser', { description: 'edited' }));
    const { catalog } = await loaded([catalogServer('browser')], { update });

    expect(await catalog.save({ name: 'browser' } as never, 'browser')).toBe('browser');
    expect(catalog.byId('browser')?.description).toBe('edited');
  });

  it('folds a newly created entry in without waiting for the next poll', async () => {
    const create = vi.fn().mockResolvedValue(catalogServer('files'));
    const { catalog } = await loaded([catalogServer('browser')], { create });

    expect(await catalog.save({ name: 'files' } as never)).toBe('files');
    expect(catalog.servers().map(s => s.id)).toEqual(['files', 'browser']);
  });

  it('reports a rejected save rather than pretending it landed', async () => {
    const { ctx, catalog } = await loaded([], {
      create: vi.fn().mockRejectedValue(new Error('port 1100 taken')),
    });

    expect(await catalog.save({ name: 'files' } as never)).toBe('');
    expect(ctx.liveError()).toBe('MCP server save failed: port 1100 taken');
    expect(catalog.servers()).toEqual([]);
  });
});

describe('McpCatalogStore lifecycle', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('shows a start as in flight before the backend has answered', async () => {
    let land!: (value: McpCatalogServer) => void;
    const run = vi.fn().mockReturnValue(new Promise<McpCatalogServer>(r => { land = r; }));
    const { catalog } = await loaded([catalogServer('browser')], { run });

    const started = catalog.start('browser');
    expect(catalog.byId('browser')?.operationState).toBe('starting');

    land(catalogServer('browser', { runtimeState: 'running', operationState: 'idle' }));
    await started;
    expect(catalog.byId('browser')?.runtimeState).toBe('running');
  });

  it('marks a check as checking without touching the operation state', async () => {
    const run = vi.fn().mockResolvedValue(
      catalogServer('browser', { checkStatus: 'connected', latencyMs: 12 }));
    const { catalog } = await loaded([catalogServer('browser', { operationState: 'idle' })], { run });

    expect(await catalog.check('browser')).toBe(true);
    expect(catalog.byId('browser')?.checkStatus).toBe('connected');
  });

  it('records a failed check on the entry instead of as an operation error', async () => {
    const run = vi.fn().mockRejectedValue(new Error('connection refused'));
    const { ctx, catalog } = await loaded([catalogServer('browser')], { run });

    expect(await catalog.check('browser')).toBe(false);
    const server = catalog.byId('browser')!;
    expect(server.checkStatus).toBe('error');
    expect(server.checkError).toBe('connection refused');
    expect(server.operationState).toBe('idle');
    expect(ctx.liveError()).toBe('MCP server check failed: connection refused');
  });

  it('leaves a failed start visible on the entry so the page can show why', async () => {
    const run = vi.fn().mockRejectedValue(new Error('image pull failed'));
    const { catalog } = await loaded([catalogServer('browser')], { run });

    expect(await catalog.start('browser')).toBe(false);
    expect(catalog.byId('browser')?.operationState).toBe('error');
    expect(catalog.byId('browser')?.operationError).toBe('image pull failed');
  });

  it('re-reads the catalog when a lifecycle call answers with no entry', async () => {
    const list = vi.fn().mockResolvedValue([catalogServer('browser', { runtimeState: 'stopped' })]);
    const { catalog } = await loaded([catalogServer('browser', { runtimeState: 'running' })], {
      list, run: vi.fn().mockResolvedValue(undefined),
    });

    await catalog.stop('browser');

    expect(catalog.byId('browser')?.runtimeState).toBe('stopped');
  });

  it('does nothing for an id the catalog does not hold', async () => {
    const run = vi.fn();
    const { catalog } = await loaded([], { run });

    expect(await catalog.apply('missing')).toBe(false);
    expect(run).not.toHaveBeenCalled();
  });

  it('waits for a real running state before reporting a start as landed', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([catalogServer('browser', { runtimeState: 'stopped' })])
      .mockResolvedValue([catalogServer('browser', { runtimeState: 'running' })]);
    const { catalog } = await loaded([catalogServer('browser')], { list });

    const waited = catalog.waitUntilRunning('browser');
    await vi.advanceTimersByTimeAsync(2_000);

    expect(await waited).toBe(true);
  });

  it('gives up on a start the backend reports as failed', async () => {
    const list = vi.fn().mockResolvedValue(
      [catalogServer('browser', { runtimeState: 'error', operationError: 'exited 1' })]);
    const { ctx, catalog } = await loaded([catalogServer('browser')], { list });

    expect(await catalog.waitUntilRunning('browser')).toBe(false);
    expect(ctx.liveError()).toBe('MCP server start failed: exited 1');
  });

  it('reports false when the entry disappears while a start is being awaited', async () => {
    const { catalog } = await loaded([catalogServer('browser')], {
      list: vi.fn().mockResolvedValue([]),
    });

    expect(await catalog.waitUntilRunning('browser')).toBe(false);
  });
});

describe('McpCatalogStore operation polling', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('keeps re-reading while an operation is still running, and stops when it lands', async () => {
    const busy = catalogServer('browser', { operationState: 'starting' });
    const idle = catalogServer('browser', { operationState: 'idle', runtimeState: 'running' });
    const list = vi.fn()
      .mockResolvedValueOnce([busy])
      .mockResolvedValueOnce([busy])
      .mockResolvedValue([idle]);
    const { catalog } = await loaded([busy], {
      list, run: vi.fn().mockResolvedValue(busy),
    });

    await catalog.start('browser');
    await vi.advanceTimersByTimeAsync(1_500);
    expect(catalog.byId('browser')?.operationState).toBe('starting');

    await vi.advanceTimersByTimeAsync(1_500);
    expect(catalog.byId('browser')?.operationState).toBe('idle');

    const settled = list.mock.calls.length;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(list.mock.calls.length).toBe(settled);
  });

  it('polls a save that came back mid-operation, without being asked to start it', async () => {
    const busy = catalogServer('browser', { operationState: 'applying' });
    const list = vi.fn()
      .mockResolvedValueOnce([])                       // nothing registered yet
      .mockResolvedValue([catalogServer('browser')]);
    const { catalog } = await loaded([], { list, create: vi.fn().mockResolvedValue(busy) });

    expect(await catalog.save({ name: 'browser' } as never)).toBe('browser');
    await vi.advanceTimersByTimeAsync(1_500);

    expect(catalog.byId('browser')?.operationState).toBe('idle');
  });

  it('runs one poll per entry, however many times it is asked', async () => {
    const busy = catalogServer('browser', { operationState: 'starting' });
    const list = vi.fn().mockResolvedValue([busy]);
    const { catalog } = await loaded([busy], { list, run: vi.fn().mockResolvedValue(busy) });

    await catalog.start('browser');
    await catalog.start('browser');
    const before = list.mock.calls.length;
    await vi.advanceTimersByTimeAsync(1_500);

    // two starts, but the entry is polled once — a second poll would double the reads
    expect(list.mock.calls.length).toBe(before + 1);
  });

  it('re-reads what a delete left behind once the entry is gone', async () => {
    const retained = vi.fn().mockResolvedValue([]);
    const { catalog } = await loaded([catalogServer('browser')], {
      remove: vi.fn().mockResolvedValue(undefined),
      list: vi.fn()
        .mockResolvedValueOnce([catalogServer('browser')])   // still registered
        .mockResolvedValue([]),                              // gone after the delete
      retainedResources: retained,
    });

    expect(await catalog.remove('browser')).toBe(true);
    await vi.advanceTimersByTimeAsync(2_000);

    expect(retained).toHaveBeenCalled();
  });

  it('gives up on a start the backend reports as failed by operation state', async () => {
    const list = vi.fn().mockResolvedValue(
      [catalogServer('browser', { operationState: 'error', operationError: null })]);
    const { ctx, catalog } = await loaded([catalogServer('browser')], { list });

    expect(await catalog.waitUntilRunning('browser')).toBe(false);
    expect(ctx.liveError()).toBe('MCP server start failed: stopped');
  });
});

describe('McpCatalogStore deletion and logs', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reports a delete the backend refused, keeping the entry', async () => {
    const { ctx, catalog } = await loaded([catalogServer('browser')], {
      remove: vi.fn().mockRejectedValue(new Error('in use by 2 agents')),
    });

    expect(await catalog.remove('browser')).toBe(false);
    expect(catalog.byId('browser')).not.toBeNull();
    expect(ctx.liveError()).toBe('MCP server delete failed: in use by 2 agents');
  });

  it('ignores a delete for an id the catalog does not hold', async () => {
    const remove = vi.fn();
    const { catalog } = await loaded([], { remove });

    expect(await catalog.remove('missing')).toBe(false);
    expect(remove).not.toHaveBeenCalled();
  });

  it('shows the deleting state the backend answered with', async () => {
    const { catalog } = await loaded([catalogServer('browser')], {
      remove: vi.fn().mockResolvedValue(catalogServer('browser', { operationState: 'deleting' })),
      list: vi.fn().mockResolvedValue([catalogServer('browser', { operationState: 'deleting' })]),
    });

    expect(await catalog.remove('browser')).toBe(true);
    expect(catalog.byId('browser')?.operationState).toBe('deleting');
  });

  it('drops a purged volume from the retained inventory without a re-read', async () => {
    const retained = { id: 'vol-1', serverId: 'browser', serverName: 'browser', hostId: 'dh-local',
      type: 'volume' as const, name: 'browser-data', createdAt: 1 };
    const { catalog } = await loaded([], {
      retainedResources: vi.fn().mockResolvedValue([retained]),
      purgeRetainedResource: vi.fn().mockResolvedValue(undefined),
    });
    await catalog.refreshRetainedResources();

    expect(await catalog.purgeRetainedResource('vol-1')).toBe(true);
    expect(catalog.retainedResources()).toEqual([]);
  });

  it('keeps a volume the purge failed on, and says why', async () => {
    const { ctx, catalog } = await loaded([], {
      purgeRetainedResource: vi.fn().mockRejectedValue(new Error('volume in use')),
    });

    expect(await catalog.purgeRetainedResource('vol-1')).toBe(false);
    expect(ctx.liveError()).toBe('retained resource purge failed: volume in use');
  });

  it('reads a managed server log newest first, and refuses one that has no container', async () => {
    const logs = vi.fn().mockResolvedValue([
      { ts: 1, level: 'info', source: 'mcp', msg: 'older' },
      { ts: 9, level: 'warn', source: 'mcp', msg: 'newer' },
    ]);
    const { catalog } = await loaded(
      [catalogServer('browser'), catalogServer('remote', { kind: 'external' })], { logs });

    expect((await catalog.logTail('browser')).map(l => l.msg)).toEqual(['newer', 'older']);
    expect(await catalog.logTail('remote')).toEqual([]);
    expect(await catalog.logTail('missing')).toEqual([]);
  });
});
