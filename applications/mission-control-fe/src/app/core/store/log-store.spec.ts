import { describe, expect, it, vi } from 'vitest';
import { apiContainer, testSlices } from '../../testing/store';

const line = (ts: number, msg: string) => ({ ts, level: 'info' as const, source: 'container', msg });

/** Two containers, with the docker log endpoint stubbed. */
const loaded = async (logs: unknown, containers = [apiContainer(), apiContainer({ id: 'c-2' })]) => {
  const { ctx, containers: containerStore, logs: store } = testSlices({
    containers: { list: vi.fn().mockResolvedValue(containers), logs },
  });
  await containerStore.refresh();
  return { ctx, containers: containerStore, store };
};

describe('LogStore', () => {
  it('shows the selected container\'s tail, newest first', async () => {
    const { store, containers } = await loaded(
      vi.fn().mockResolvedValue([line(1, 'older'), line(9, 'newer')]));

    containers.select('c-1');
    await store.poll();

    expect(store.selectedLogs().map(l => l.msg)).toEqual(['newer', 'older']);
    expect(store.updatedAt()).not.toBeNull();
  });

  it('shows nothing for a container it has never read', async () => {
    const { store, containers } = await loaded(vi.fn().mockResolvedValue([]));

    containers.select('c-2');

    expect(store.selectedLogs()).toEqual([]);
  });

  it('keeps each container\'s tail, so switching back is immediate', async () => {
    // answered per container, not per call: the first inventory selects one, and that
    // selection reads its tail before the test asks for anything
    const logs = vi.fn().mockImplementation(
      (_host: string, id: string) => Promise.resolve([line(1, `from ${id}`)]));
    const { store, containers } = await loaded(logs);
    containers.select('c-1');
    await store.poll();
    containers.select('c-2');
    await store.poll();

    containers.select('c-1');

    expect(store.selectedLogs().map(l => l.msg)).toEqual(['from c-1']);
  });

  it('never reads a stopped container, which has no stream to tail', async () => {
    const logs = vi.fn();
    const { store, containers } = await loaded(logs, [apiContainer({ status: 'stopped' })]);
    containers.select('c-1');

    await store.poll();

    expect(logs).not.toHaveBeenCalled();
  });

  it('skips a tick rather than overlapping two reads of one container', async () => {
    const logs = vi.fn().mockReturnValue(new Promise(() => { /* never settles */ }));
    const { store, containers } = await loaded(logs);
    containers.select('c-1');

    void store.poll();
    await store.poll();

    expect(logs).toHaveBeenCalledTimes(1);
  });

  it('clears the previous container\'s loading story on selection', async () => {
    const { store, containers } = await loaded(
      vi.fn().mockResolvedValue([line(1, 'first')]));
    containers.select('c-1');
    await store.poll();
    expect(store.updatedAt()).not.toBeNull();

    containers.select('c-2');

    // the previous container's tail must not read as this one's
    expect(store.updatedAt()).toBeNull();
    expect(store.error()).toBeNull();
  });

  it('says why a read failed, for the container the operator is looking at', async () => {
    const { store, containers } = await loaded(
      vi.fn().mockRejectedValue(new Error('daemon gone')));
    containers.select('c-1');

    await store.poll();

    expect(store.error()).toBe('daemon gone');
    expect(store.loading()).toBe(false);
  });

  it('names a rejection that carried no message at all', async () => {
    const { store, containers } = await loaded(vi.fn().mockRejectedValue(null));
    containers.select('c-1');

    await store.poll();

    expect(store.error()).toBe('log refresh failed');
  });

  it('does not repaint the page for a read that landed after the operator moved on', async () => {
    let land!: (value: unknown[]) => void;
    const logs = vi.fn()
      .mockReturnValueOnce(new Promise(resolve => { land = resolve; }))
      // c-2's own read stays open, so only the stale response could repaint
      .mockReturnValue(new Promise(() => { /* never settles */ }));
    const { store, containers } = await loaded(logs);
    containers.select('c-1');
    const pending = store.poll();

    containers.select('c-2');
    land([line(1, 'from c-1')]);
    await pending;

    // the tail is still cached against c-1, but c-2's own state is untouched
    expect(store.updatedAt()).toBeNull();
    expect(store.selectedLogs()).toEqual([]);
    containers.select('c-1');
    expect(store.selectedLogs().map(l => l.msg)).toEqual(['from c-1']);
  });

  it('does not surface a failure the operator has already navigated away from', async () => {
    let fail!: (reason: Error) => void;
    const logs = vi.fn()
      .mockReturnValueOnce(new Promise((_, reject) => { fail = reject; }))
      .mockResolvedValue([]);
    const { store, containers } = await loaded(logs);
    containers.select('c-1');
    const pending = store.poll();

    containers.select('c-2');
    fail(new Error('daemon gone'));
    await pending;

    expect(store.error()).toBeNull();
    expect(store.loading()).toBe(false);
  });

  it('fetches immediately on selection rather than waiting out the poll', async () => {
    const logs = vi.fn().mockResolvedValue([]);
    const { containers } = await loaded(logs);

    containers.select('c-1');

    // no cursor on a fresh selection: the whole tail is re-read rather than resumed
    expect(logs).toHaveBeenCalledWith('dh-local', 'c-1', 100, undefined);
  });
});
