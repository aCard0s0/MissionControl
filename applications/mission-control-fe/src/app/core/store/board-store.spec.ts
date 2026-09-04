import { describe, expect, it, vi } from 'vitest';
import { ApiBoardTask } from '../hermes-api';
import { apiContainer, flush, liveError, testSlices } from '../../testing/store';

const task = (id: string, patch: Partial<ApiBoardTask> = {}): ApiBoardTask => ({
  id, containerId: 'c-1', agentId: 'a-atlas', title: `task ${id}`, column: 'queued',
  priority: 'med', tags: ['ops'], createdAt: 1_000, ...patch,
});

/** A board holding `tasks`, scoped to the selected container. */
const loaded = async (tasks: ApiBoardTask[], board: Record<string, unknown> = {}) => {
  const { ctx, containers, board: store } = testSlices({
    containers: { list: vi.fn().mockResolvedValue([apiContainer()]) },
    board: { tasks: vi.fn().mockResolvedValue(tasks), ...board },
  });
  await containers.refresh();
  containers.select('c-1');
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
    expect(liveError(ctx)).toBeNull();
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
    expect(liveError(ctx)).toBe('move failed: task locked');
  });

  it('rolls back only the refused card, not a move that landed meanwhile', async () => {
    // the rollback used to restore a whole-board snapshot, which also reverted a second
    // card the backend had already accepted — and the board is loaded once, not polled,
    // so the screen disagreed with the backend until a reload
    let refuse!: (reason: unknown) => void;
    const failing = new Promise((_resolve, reject) => { refuse = reject; });
    const moveTask = vi.fn()
      .mockImplementationOnce(() => failing)
      .mockResolvedValue(undefined);
    const { store } = await loaded([task('t-1'), task('t-2')], { moveTask });

    store.move('t-1', 'done');      // refused, slowly
    store.move('t-2', 'review');    // lands while t-1 is still in flight
    refuse(new Error('task locked'));
    await flush();

    expect(store.tasks().map(t => t.column)).toEqual(['queued', 'review']);
  });

  it('ignores a drop for a card that was removed between the render and the drop', async () => {
    const moveTask = vi.fn();
    const { store } = await loaded([task('t-1')], { moveTask });

    store.move('t-ghost', 'done');

    expect(moveTask).not.toHaveBeenCalled();
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
