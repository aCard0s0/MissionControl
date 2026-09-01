import { describe, expect, it, vi } from 'vitest';
import { ApiDeployedPart, ApiMcpGroup } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiGroup = (id: string, patch: Partial<ApiMcpGroup> = {}): ApiMcpGroup => ({
  id, name: `group-${id}`, description: 'everything a researcher needs',
  serverIds: ['m-1', 'm-2'],
  agents: [{ hostId: 'dh-local', containerId: 'c-1', profile: 'atlas', linked: 2 }],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const part = (patch: Partial<ApiDeployedPart> = {}): ApiDeployedPart =>
  ({ kind: 'mcp', name: 'files', status: 'deployed', detail: null, ...patch });

const AGENT = { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' };

const loaded = async (groups: ApiMcpGroup[], api: Record<string, unknown> = {}) => {
  const { ctx, mcpGroups: store } = testSlices({
    mcpGroups: { list: vi.fn().mockResolvedValue(groups), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('McpGroupStore', () => {
  it('reads the groups and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([
      apiGroup('mg-1', { description: null, serverIds: null, agents: null }),
    ]);

    expect(store.groups()[0]).toMatchObject({
      id: 'mg-1', description: '', serverIds: [], agents: [],
    });
  });

  it('keeps the agent coverage the backend derived', async () => {
    const { store } = await loaded([apiGroup('mg-1')]);

    expect(store.groups()[0].agents).toEqual([
      { hostId: 'dh-local', containerId: 'c-1', profile: 'atlas', linked: 2 },
    ]);
  });

  it('keeps the last groups when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiGroup('mg-1')], {
      list: vi.fn().mockResolvedValueOnce([apiGroup('mg-1')])
        .mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.groups().map(g => g.id)).toEqual(['mg-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('keeps the list by name when a save lands, so headers do not jump', async () => {
    const { store } = await loaded(
      [apiGroup('mg-1', { name: 'alpha' }), apiGroup('mg-2', { name: 'zebra' })],
      { create: vi.fn().mockResolvedValue(apiGroup('mg-3', { name: 'middle' })) });

    await store.save({ name: 'middle', description: '', serverIds: [] });

    expect(store.groups().map(g => g.name)).toEqual(['alpha', 'middle', 'zebra']);
  });

  it('reports a failed save and keeps the group list untouched', async () => {
    const { store, ctx } = await loaded([apiGroup('mg-1')], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    expect(await store.save({ name: 'research', description: '', serverIds: [] })).toBe('');
    expect(store.groups().map(g => g.id)).toEqual(['mg-1']);
    expect(liveError(ctx)).toContain('save MCP group');
  });

  it('drops the row once a delete lands', async () => {
    const { store } = await loaded([apiGroup('mg-1'), apiGroup('mg-2')], {
      remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await store.remove('mg-1')).toBe(true);
    expect(store.groups().map(g => g.id)).toEqual(['mg-2']);
  });

  it('returns the per-part report so a half-connected group is not read as a success',
    async () => {
      const { store } = await loaded([apiGroup('mg-1')], {
        deploy: vi.fn().mockResolvedValue({
          profile: {},
          parts: [
            part({ name: 'files' }),
            part({ name: 'gone', status: 'skipped', detail: 'no longer in the catalog' }),
          ],
        }),
      });

      const parts = await store.deploy('mg-1', AGENT);

      expect(parts).toEqual([
        { kind: 'mcp', name: 'files', status: 'deployed', detail: '' },
        { kind: 'mcp', name: 'gone', status: 'skipped', detail: 'no longer in the catalog' },
      ]);
    });

  it('re-reads the groups after a deploy, because the coverage counts come off the links',
    async () => {
      const list = vi.fn().mockResolvedValue([apiGroup('mg-1')]);
      const { store } = await loaded([apiGroup('mg-1')], {
        list,
        deploy: vi.fn().mockResolvedValue({ profile: {}, parts: [part()] }),
      });
      const readsBefore = list.mock.calls.length;

      await store.deploy('mg-1', AGENT);

      expect(list.mock.calls.length).toBe(readsBefore + 1);
    });

  it('answers null and does not re-read when the deploy request itself fails', async () => {
    const list = vi.fn().mockResolvedValue([apiGroup('mg-1')]);
    const { store, ctx } = await loaded([apiGroup('mg-1')], {
      list,
      deploy: vi.fn().mockRejectedValue(new Error('host unreachable')),
    });
    const readsBefore = list.mock.calls.length;

    expect(await store.deploy('mg-1', AGENT)).toBeNull();
    expect(list.mock.calls.length).toBe(readsBefore);
    expect(liveError(ctx)).toContain('deploy MCP group');
  });
});
