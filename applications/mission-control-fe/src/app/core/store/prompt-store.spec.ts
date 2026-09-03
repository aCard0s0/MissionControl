import { describe, expect, it, vi } from 'vitest';
import { ApiPrompt } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiPrompt = (id: string, patch: Partial<ApiPrompt> = {}): ApiPrompt => ({
  id, title: `prompt ${id}`, body: 'read the first error', category: 'ops',
  notes: 'read only', tags: ['ops'], createdAt: 1_000, updatedAt: 2_000, ...patch,
});

/** A library holding `prompts`. */
const loaded = async (prompts: ApiPrompt[], api: Record<string, unknown> = {}) => {
  const { ctx, prompts: store } = testSlices({
    prompts: { list: vi.fn().mockResolvedValue(prompts), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('PromptStore', () => {
  it('reads the library and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([apiPrompt('p-1', { notes: null, category: '' })]);

    expect(store.prompts()[0]).toMatchObject({
      id: 'p-1', notes: '', category: 'general',
    });
  });

  it('keeps the last library when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiPrompt('p-1')], {
      list: vi.fn().mockResolvedValueOnce([apiPrompt('p-1')]).mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.prompts().map(p => p.id)).toEqual(['p-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('lists every category in use once, sorted, for the filter chips', async () => {
    const { store } = await loaded([
      apiPrompt('p-1', { category: 'review' }),
      apiPrompt('p-2', { category: 'ops' }),
      apiPrompt('p-3', { category: 'ops' }),
    ]);

    expect(store.categories()).toEqual(['ops', 'review']);
  });

  it('creates a prompt and puts it at the top of the library', async () => {
    const create = vi.fn().mockResolvedValue(apiPrompt('p-new', { title: 'Triage' }));
    const { store } = await loaded([apiPrompt('p-1')], { create });

    const id = await store.save({
      title: 'Triage', body: 'b', category: 'ops', notes: '', tags: ['ops'],
    });

    expect(id).toBe('p-new');
    expect(create).toHaveBeenCalledWith({
      title: 'Triage', body: 'b', category: 'ops', notes: '', tags: ['ops'],
    });
    expect(store.prompts().map(p => p.id)).toEqual(['p-new', 'p-1']);
  });

  it('edits in place through a PUT rather than adding a second copy', async () => {
    const update = vi.fn().mockResolvedValue(apiPrompt('p-1', { title: 'Triage v2' }));
    const { store } = await loaded([apiPrompt('p-1'), apiPrompt('p-2')], { update });

    const id = await store.save({
      title: 'Triage v2', body: 'b', category: 'ops', notes: '', tags: [],
    }, 'p-1');

    expect(id).toBe('p-1');
    expect(update).toHaveBeenCalledWith('p-1', expect.objectContaining({ title: 'Triage v2' }));
    expect(store.prompts().map(p => p.id)).toEqual(['p-1', 'p-2']);
    expect(store.byId('p-1')?.title).toBe('Triage v2');
  });

  it('says why a save failed and leaves the library untouched', async () => {
    const { store, ctx } = await loaded([apiPrompt('p-1')], {
      create: vi.fn().mockRejectedValue(new Error('disk full')),
    });

    const id = await store.save({ title: 't', body: 'b', category: '', notes: '', tags: [] });

    expect(id).toBe('');
    expect(store.prompts().map(p => p.id)).toEqual(['p-1']);
    expect(liveError(ctx)).toBe('save prompt failed: disk full');
  });

  it('drops a deleted prompt and reports that it is gone', async () => {
    const { store } = await loaded([apiPrompt('p-1'), apiPrompt('p-2')], {
      remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await store.remove('p-1')).toBe(true);
    expect(store.prompts().map(p => p.id)).toEqual(['p-2']);
  });

  it('keeps a prompt the backend refused to delete', async () => {
    const { store, ctx } = await loaded([apiPrompt('p-1')], {
      remove: vi.fn().mockRejectedValue(new Error('locked')),
    });

    expect(await store.remove('p-1')).toBe(false);
    expect(store.prompts().map(p => p.id)).toEqual(['p-1']);
    expect(liveError(ctx)).toBe('delete prompt failed: locked');
  });

  it('answers null for an id the library does not hold', async () => {
    const { store } = await loaded([apiPrompt('p-1')]);

    expect(store.byId('p-nope')).toBeNull();
    expect(store.byId(null)).toBeNull();
  });
});
