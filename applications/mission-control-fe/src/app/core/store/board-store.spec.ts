import { describe, expect, it, vi } from 'vitest';
import { ApiBoardTask } from '../hermes-api';
import { BoardStore } from './board-store';
import { ContainerStore } from './container-store';
import { apiContainer, flush, testContext } from '../../testing/store';

const task = (id: string, patch: Partial<ApiBoardTask> = {}): ApiBoardTask => ({
  id, containerId: 'c-1', agentId: 'a-atlas', title: `task ${id}`, column: 'queued',
  priority: 'med', tags: ['ops'], createdAt: 1_000, ...patch,
});

/** A board holding `tasks`, scoped to the selected container. */
const loaded = async (tasks: ApiBoardTask[], board: Record<string, unknown> = {}) => {
  const ctx = testContext();
  const containers = new ContainerStore(ctx);
  (ctx as unknown as { api: unknown }).api = {
    containers: { list: vi.fn().mockResolvedValue([apiContainer()]) },
    board: { tasks: vi.fn().mockResolvedValue(tasks), ...board },
  };
  await containers.refresh();
  containers.select('c-1');
  const store = new BoardStore(ctx, containers);
  await store.refresh();
  return { ctx, containers, store };
};

describe('BoardStore', () => {
  it('shows only the selected container\'s tasks', async () => {
    const { store, containers } = await loaded([
      task('t-1'), task('t-2', { containerId: 'c-2' }),
    ]);

    expect(store.forSelectedContainer().map(t => t.id)).toEqual(['t-1']);
    containers.select('c-2');
    expect(store.forSelectedContainer().map(t => t.id)).toEqual(['t-2']);
  });

  it('fills in the fields an unassigned task leaves null', async () => {
    const { store } = await loaded([task('t-1', { agentId: null, tags: null as never })]);

    expect(store.tasks()[0]).toMatchObject({ agentId: '', tags: [] });
  });

  it('keeps the last board when a read fails — the board is not worth an error', async () => {
    const { store, ctx } = await loaded([task('t-1')], {
      tasks: vi.fn().mockResolvedValueOnce([task('t-1')]).mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.tasks().map(t => t.id)).toEqual(['t-1']);
    expect(ctx.liveError()).toBeNull();
  });

  it('moves a card immediately, before the backend has agreed', async () => {
    const moveTask = vi.fn().mockReturnValue(new Promise(() => { /* never settles */ }));
    const { store } = await loaded([task('t-1')], { moveTask });

    store.move('t-1', 'done');

    expect(store.tasks()[0].column).toBe('done');
    expect(moveTask).toHaveBeenCalledWith('t-1', 'done');
  });

  it('puts the card back where it was when the move is refused', async () => {
    const { store, ctx } = await loaded([task('t-1'), task('t-2', { column: 'done' })], {
      moveTask: vi.fn().mockRejectedValue(new Error('task locked')),
    });

    store.move('t-1', 'done');
    await flush();

    expect(store.tasks().map(t => t.column)).toEqual(['queued', 'done']);
    expect(ctx.liveError()).toBe('move failed: task locked');
  });

  it('drops the tasks of a container or a profile that is gone', async () => {
    const { store } = await loaded([
      task('t-1'), task('t-2', { agentId: 'a-scribe' }), task('t-3', { containerId: 'c-2' }),
    ]);

    store.dropByAgent('a-scribe');
    expect(store.tasks().map(t => t.id)).toEqual(['t-1', 't-3']);

    store.dropByContainer('c-1');
    expect(store.tasks().map(t => t.id)).toEqual(['t-3']);
  });

  it('re-keys tasks onto a container that was recreated under a new id', async () => {
    const { store } = await loaded([task('t-1'), task('t-2', { containerId: 'c-2' })]);

    store.reassignContainer('c-1', 'c-new');

    expect(store.tasks().map(t => t.containerId)).toEqual(['c-new', 'c-2']);
  });
});
