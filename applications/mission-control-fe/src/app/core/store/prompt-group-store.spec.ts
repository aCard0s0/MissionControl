import { describe, expect, it, vi } from 'vitest';
import { ApiPromptGroup } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiGroup = (id: string, patch: Partial<ApiPromptGroup> = {}): ApiPromptGroup => ({
  id, name: `group-${id}`, description: 'everything for a bad deploy',
  promptIds: ['p-1'], createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const loaded = async (groups: ApiPromptGroup[], api: Record<string, unknown> = {}) => {
  const { ctx, promptGroups: store } = testSlices({
    promptGroups: { list: vi.fn().mockResolvedValue(groups), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('PromptGroupStore', () => {
  it('reads the groups and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([apiGroup('pg-1', { description: null, promptIds: null })]);

    expect(store.groups()[0]).toMatchObject({ id: 'pg-1', description: '', promptIds: [] });
  });

  it('keeps the last groups when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiGroup('pg-1')], {
      list: vi.fn().mockResolvedValueOnce([apiGroup('pg-1')])
        .mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.groups().map(g => g.id)).toEqual(['pg-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('keeps the list by name when a save lands, so headers do not jump', async () => {
    const { store } = await loaded(
      [apiGroup('pg-1', { name: 'alpha' }), apiGroup('pg-2', { name: 'zebra' })],
      { create: vi.fn().mockResolvedValue(apiGroup('pg-3', { name: 'middle' })) });

    await store.save({ name: 'middle', description: '', promptIds: [] });

    expect(store.groups().map(g => g.name)).toEqual(['alpha', 'middle', 'zebra']);
  });

  it('replaces the row it saved rather than adding a second one', async () => {
    const { store } = await loaded([apiGroup('pg-1', { name: 'triage' })], {
      update: vi.fn().mockResolvedValue(apiGroup('pg-1', { name: 'triage-v2' })),
    });

    await store.save({ name: 'triage-v2', description: '', promptIds: [] }, 'pg-1');

    expect(store.groups().map(g => g.name)).toEqual(['triage-v2']);
  });

  it('reports a failed save and keeps the group list untouched', async () => {
    const { store, ctx } = await loaded([apiGroup('pg-1')], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    expect(await store.save({ name: 'triage', description: '', promptIds: [] })).toBe('');
    expect(store.groups().map(g => g.id)).toEqual(['pg-1']);
    expect(liveError(ctx)).toContain('save prompt group');
  });

  it('drops the row once a delete lands', async () => {
    const { store } = await loaded([apiGroup('pg-1'), apiGroup('pg-2')], {
      remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await store.remove('pg-1')).toBe(true);
    expect(store.groups().map(g => g.id)).toEqual(['pg-2']);
  });

  it('keeps the row when a delete fails', async () => {
    const { store, ctx } = await loaded([apiGroup('pg-1')], {
      remove: vi.fn().mockRejectedValue(new Error('offline')),
    });

    expect(await store.remove('pg-1')).toBe(false);
    expect(store.groups().map(g => g.id)).toEqual(['pg-1']);
    expect(liveError(ctx)).toContain('delete prompt group');
  });
});
