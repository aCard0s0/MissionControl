import { describe, expect, it, vi } from 'vitest';
import { ApiSkillGroup } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiGroup = (id: string, patch: Partial<ApiSkillGroup> = {}): ApiSkillGroup => ({
  id, name: `group-${id}`, description: 'everything that touches a PDF',
  skillIds: ['s-1'], guideId: 'g-1', createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const loaded = async (groups: ApiSkillGroup[], api: Record<string, unknown> = {}) => {
  const { ctx, skillGroups: store } = testSlices({
    skillGroups: { list: vi.fn().mockResolvedValue(groups), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('SkillGroupStore', () => {
  it('reads the groups and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([
      apiGroup('sg-1', { description: null, skillIds: [], guideId: null }),
    ]);

    expect(store.groups()[0]).toMatchObject({
      id: 'sg-1', description: '', skillIds: [], guideId: '',
    });
  });

  it('keeps the last groups when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiGroup('sg-1')], {
      list: vi.fn().mockResolvedValueOnce([apiGroup('sg-1')])
        .mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.groups().map(g => g.id)).toEqual(['sg-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('keeps the list by name when a save lands, so headers do not jump', async () => {
    // these are the headers the skills list is filed under: re-sorting on an edit would
    // move every skill beneath one
    const { store } = await loaded(
      [apiGroup('sg-1', { name: 'alpha' }), apiGroup('sg-2', { name: 'zebra' })],
      { create: vi.fn().mockResolvedValue(apiGroup('sg-3', { name: 'middle' })) });

    await store.save({ name: 'middle', description: '', skillIds: [], guideId: '' });

    expect(store.groups().map(g => g.name)).toEqual(['alpha', 'middle', 'zebra']);
  });

  it('replaces the row it saved rather than adding a second one', async () => {
    const { store } = await loaded([apiGroup('sg-1', { name: 'pdf' })], {
      update: vi.fn().mockResolvedValue(apiGroup('sg-1', { name: 'pdf-tools' })),
    });

    await store.save({ name: 'pdf-tools', description: '', skillIds: [], guideId: '' }, 'sg-1');

    expect(store.groups().map(g => g.name)).toEqual(['pdf-tools']);
  });

  it('reports a failed save and keeps the group list untouched', async () => {
    const { store, ctx } = await loaded([apiGroup('sg-1')], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    expect(await store.save({ name: 'pdf', description: '', skillIds: [], guideId: '' }))
      .toBe('');
    expect(store.groups().map(g => g.id)).toEqual(['sg-1']);
    expect(liveError(ctx)).toContain('save skill group');
  });

  it('drops the row once a delete lands', async () => {
    const { store } = await loaded([apiGroup('sg-1'), apiGroup('sg-2')], {
      remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await store.remove('sg-1')).toBe(true);
    expect(store.groups().map(g => g.id)).toEqual(['sg-2']);
  });

  it('keeps the row when a delete fails', async () => {
    const { store, ctx } = await loaded([apiGroup('sg-1')], {
      remove: vi.fn().mockRejectedValue(new Error('offline')),
    });

    expect(await store.remove('sg-1')).toBe(false);
    expect(store.groups().map(g => g.id)).toEqual(['sg-1']);
    expect(liveError(ctx)).toContain('delete skill group');
  });
});
