import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiAgentProfile, ApiMcpServer } from '../hermes-api';
import { AgentMcpStore } from './agent-mcp-store';
import { McpCatalogStore } from './mcp-catalog-store';
import { catalogServer } from '../../testing/models';
import { apiProfile, loadedAgentSlices, stubBackend } from '../../testing/store';

const server = (name: string, patch: Partial<ApiMcpServer> = {}): ApiMcpServer => ({
  id: `m-${name}`, name, transport: 'http', enabled: true, origin: 'custom',
  catalogServerId: null, status: 'connected', tools: 4, latencyMs: 30,
  url: `http://${name}:1100/mcp`, ...patch,
});

/** The refreshed profile every mutation answers with. */
const withServers = (servers: ApiMcpServer[]): ApiAgentProfile =>
  apiProfile('atlas', { mcp: servers });

/**
 * A profile holding `servers`, plus the catalog slice the connect/sync verbs
 * reach through. `mcp` stubs the profile-scoped MCP client; `catalog` the
 * global one.
 */
const loaded = async (
  servers: ApiMcpServer[],
  mcp: Record<string, unknown> = {},
  catalogEntries = [catalogServer('browser')],
) => {
  const slices = await loadedAgentSlices(
    { agents: { mcp } }, { profiles: [withServers(servers)] });
  const catalog = new McpCatalogStore(slices.ctx);
  stubBackend(slices.ctx, {
    ...(slices.ctx.api as unknown as object),
    mcp: { list: vi.fn().mockResolvedValue(catalogEntries), run: vi.fn() },
  });
  await catalog.refresh();
  return { ...slices, catalog, store: new AgentMcpStore(slices.ctx, slices.agents, catalog) };
};

describe('AgentMcpStore direct servers', () => {
  it('adds a server with the endpoint the form supplied', async () => {
    const add = vi.fn().mockResolvedValue(withServers([server('github')]));
    const { store, agents } = await loaded([], { add });

    expect(await store.add('a-atlas', 'github', 'http', { url: 'http://gh' })).toBe(true);
    expect(add).toHaveBeenCalledWith(expect.objectContaining({ name: 'atlas' }),
      expect.objectContaining({ name: 'github', transport: 'http', url: 'http://gh' }));
    expect(agents.byId('a-atlas')?.mcp.map(m => m.name)).toEqual(['github']);
  });

  it('refuses to add to a profile it does not hold', async () => {
    const add = vi.fn();
    const { store } = await loaded([], { add });

    expect(await store.add('a-ghost', 'github', 'http')).toBe(false);
    expect(add).not.toHaveBeenCalled();
  });

  it('reports a rejected add and says which action failed', async () => {
    const { store, ctx } = await loaded([], {
      add: vi.fn().mockRejectedValue(new Error('bad url')),
    });

    expect(await store.add('a-atlas', 'github', 'http')).toBe(false);
    expect(ctx.liveError()).toBe('mcp add failed: bad url');
  });

  it('carries the enabled flag through a rename so an edit cannot silently re-enable', async () => {
    const update = vi.fn().mockResolvedValue(withServers([server('gh')]));
    const { store } = await loaded([server('github', { enabled: false, status: 'disabled' })], { update });

    expect(await store.update('a-atlas', 'github', 'gh', 'http', { url: 'http://gh' })).toBe(true);
    expect(update).toHaveBeenCalledWith(expect.anything(), 'github',
      expect.objectContaining({ name: 'gh', enabled: false }));
  });

  it('refuses a rename onto a name the profile already uses', async () => {
    const update = vi.fn();
    const { store, ctx } = await loaded([server('github'), server('files')], { update });

    expect(await store.update('a-atlas', 'github', 'files', 'http')).toBe(false);
    expect(update).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBe('MCP alias already exists: files');
  });

  it('refuses to edit a server the profile does not have', async () => {
    const update = vi.fn();
    const { store } = await loaded([server('github')], { update });

    expect(await store.update('a-atlas', 'missing', 'gh', 'http')).toBe(false);
    expect(update).not.toHaveBeenCalled();
  });

  it('toggles a server it holds and refuses one it does not', async () => {
    const setEnabled = vi.fn().mockResolvedValue(withServers([server('github', { enabled: false })]));
    const { store, agents } = await loaded([server('github')], { setEnabled });

    expect(await store.setEnabled('a-atlas', 'github', false)).toBe(true);
    expect(agents.byId('a-atlas')?.mcp[0].enabled).toBe(false);
    expect(await store.setEnabled('a-atlas', 'missing', true)).toBe(false);
    expect(setEnabled).toHaveBeenCalledTimes(1);
  });

  it('removes by id but addresses the backend by name', async () => {
    const remove = vi.fn().mockResolvedValue(withServers([]));
    const { store } = await loaded([server('github')], { remove });

    expect(await store.remove('a-atlas', 'm-github')).toBe(true);
    expect(remove).toHaveBeenCalledWith(expect.anything(), 'github');
    expect(await store.remove('a-atlas', 'm-missing')).toBe(false);
  });
});

describe('AgentMcpStore catalog links', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('connects a running catalog server under the requested alias', async () => {
    const connectCatalog = vi.fn().mockResolvedValue(
      withServers([server('browser', { origin: 'catalog', catalogServerId: 'browser' })]));
    const { store, agents } = await loaded([], { connectCatalog },
      [catalogServer('browser', { runtimeState: 'running' })]);

    expect(await store.connectCatalog('a-atlas', 'browser', ' browser ')).toBe(true);
    expect(connectCatalog).toHaveBeenCalledWith(expect.anything(), 'browser', 'browser');
    expect(agents.byId('a-atlas')?.mcp[0].origin).toBe('catalog');
  });

  it('starts a stopped managed server, and only writes config once it is running', async () => {
    const connectCatalog = vi.fn().mockResolvedValue(withServers([server('browser')]));
    const running = catalogServer('browser', { runtimeState: 'running' });
    const { store, ctx } = await loaded([], { connectCatalog },
      [catalogServer('browser', { runtimeState: 'stopped' })]);
    stubBackend(ctx, {
      ...(ctx.api as unknown as object),
      mcp: { list: vi.fn().mockResolvedValue([running]), run: vi.fn().mockResolvedValue(running) },
    });

    expect(await store.connectCatalog('a-atlas', 'browser', 'browser')).toBe(true);
    expect(connectCatalog).toHaveBeenCalled();
  });

  it('does not write config when the server never comes up', async () => {
    const connectCatalog = vi.fn();
    const failed = catalogServer('browser', { runtimeState: 'error', operationError: 'exited 1' });
    const { store, ctx } = await loaded([], { connectCatalog },
      [catalogServer('browser', { runtimeState: 'stopped' })]);
    stubBackend(ctx, {
      ...(ctx.api as unknown as object),
      mcp: { list: vi.fn().mockResolvedValue([failed]), run: vi.fn().mockResolvedValue(failed) },
    });

    expect(await store.connectCatalog('a-atlas', 'browser', 'browser')).toBe(false);
    expect(connectCatalog).not.toHaveBeenCalled();
  });

  it('refuses an alias the profile already uses', async () => {
    const connectCatalog = vi.fn();
    const { store, ctx } = await loaded([server('browser')], { connectCatalog },
      [catalogServer('browser', { runtimeState: 'running' })]);

    expect(await store.connectCatalog('a-atlas', 'browser', 'browser')).toBe(false);
    expect(ctx.liveError()).toBe('MCP alias already exists: browser');
    expect(connectCatalog).not.toHaveBeenCalled();
  });

  it('refuses a managed server on another host with no cross-host URL to reach it by', async () => {
    const connectCatalog = vi.fn();
    const { store, ctx } = await loaded([], { connectCatalog },
      [catalogServer('browser', { hostId: 'dh-edge', runtimeState: 'running' })]);

    expect(await store.connectCatalog('a-atlas', 'browser', 'browser')).toBe(false);
    expect(ctx.liveError())
      .toBe('MCP server browser needs an explicit cross-host URL for this Agent');
  });

  it('accepts a cross-host server once an explicit URL says how to reach it', async () => {
    const connectCatalog = vi.fn().mockResolvedValue(withServers([server('browser')]));
    const { store } = await loaded([], { connectCatalog },
      [catalogServer('browser', {
        hostId: 'dh-edge', runtimeState: 'running', crossHostUrl: 'http://edge:1100/mcp',
      })]);

    expect(await store.connectCatalog('a-atlas', 'browser', 'browser')).toBe(true);
  });

  it('refuses an unknown profile, an unknown catalog entry, or a blank alias', async () => {
    const connectCatalog = vi.fn();
    const { store } = await loaded([], { connectCatalog },
      [catalogServer('browser', { runtimeState: 'running' })]);

    expect(await store.connectCatalog('a-ghost', 'browser', 'browser')).toBe(false);
    expect(await store.connectCatalog('a-atlas', 'missing', 'browser')).toBe(false);
    expect(await store.connectCatalog('a-atlas', 'browser', '   ')).toBe(false);
    expect(connectCatalog).not.toHaveBeenCalled();
  });

  it('syncs only an alias that is actually linked to a catalog entry', async () => {
    const syncCatalog = vi.fn().mockResolvedValue(withServers([server('browser')]));
    const { store } = await loaded([
      server('browser', { origin: 'catalog', catalogServerId: 'browser' }),
      server('github'),
    ], { syncCatalog });

    expect(await store.syncCatalog('a-atlas', 'browser')).toBe(true);
    expect(await store.syncCatalog('a-atlas', 'github')).toBe(false);
    expect(syncCatalog).toHaveBeenCalledTimes(1);
  });

  it('unlinks an alias it holds so it can be edited directly', async () => {
    const unlinkCatalog = vi.fn().mockResolvedValue(withServers([server('browser')]));
    const { store } = await loaded([
      server('browser', { origin: 'catalog', catalogServerId: 'browser' })], { unlinkCatalog });

    expect(await store.unlinkCatalog('a-atlas', 'browser')).toBe(true);
    expect(await store.unlinkCatalog('a-atlas', 'missing')).toBe(false);
    expect(unlinkCatalog).toHaveBeenCalledTimes(1);
  });
});

describe('AgentMcpStore probing', () => {
  const result = (patch: Record<string, unknown> = {}) => ({
    name: 'github', status: 'connected', tools: 7, latencyMs: 12, error: null,
    checkedAt: 5_000, ...patch,
  });

  it('shows the row as checking while the probe is in flight', async () => {
    let land!: (value: unknown) => void;
    const test = vi.fn().mockReturnValue(new Promise(r => { land = r; }));
    const { store, agents } = await loaded([server('github', { status: 'unknown' })], { test });

    const probing = store.test('a-atlas', 'github');
    expect(agents.byId('a-atlas')?.mcp[0].status).toBe('checking');

    land(result());
    expect(await probing).toBe(true);
    expect(agents.byId('a-atlas')?.mcp[0]).toMatchObject({ status: 'connected', tools: 7 });
  });

  it('leaves a disabled server disabled rather than showing it as checking', async () => {
    const test = vi.fn().mockResolvedValue(result({ status: 'disabled' }));
    const { store, agents } = await loaded(
      [server('github', { enabled: false, status: 'disabled' })], { test });

    const probing = store.test('a-atlas', 'github');
    expect(agents.byId('a-atlas')?.mcp[0].status).toBe('disabled');
    await probing;
  });

  it('surfaces the reason a reachable server reported an error', async () => {
    const test = vi.fn().mockResolvedValue(result({ status: 'error', error: 'unauthorized' }));
    const { store, ctx } = await loaded([server('github')], { test });

    expect(await store.test('a-atlas', 'github')).toBe(false);
    expect(ctx.liveError()).toBe('mcp github: unauthorized');
  });

  it('records a probe that could not be made on the row itself', async () => {
    const test = vi.fn().mockRejectedValue(new Error('gateway timeout'));
    const { store, agents, ctx } = await loaded([server('github')], { test });

    expect(await store.test('a-atlas', 'github')).toBe(false);
    expect(agents.byId('a-atlas')?.mcp[0]).toMatchObject({
      status: 'error', error: 'gateway timeout', latencyMs: null,
    });
    expect(ctx.liveError()).toBe('mcp test failed: gateway timeout');
  });

  it('refuses to probe on behalf of a profile it does not hold', async () => {
    const test = vi.fn();
    const { store } = await loaded([server('github')], { test });

    expect(await store.test('a-ghost', 'github')).toBe(false);
    expect(test).not.toHaveBeenCalled();
  });
});
