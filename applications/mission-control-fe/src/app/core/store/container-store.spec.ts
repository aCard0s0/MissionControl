import { describe, expect, it, vi } from 'vitest';
import { apiContainer, testSlices } from '../../testing/store';

/** A store over `list`, with the rest of the container client stubbed per test. */
const loaded = async (containers: unknown[], api: Record<string, unknown> = {}) => {
  const { ctx, containers: store } = testSlices({
    containers: { list: vi.fn().mockResolvedValue(containers), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('ContainerStore inventory', () => {
  it('maps the reported size onto disk, since a daemon reports no quota', async () => {
    const { store } = await loaded([apiContainer({ sizeRootFsGb: 7 })]);

    expect(store.containers()[0]).toMatchObject({ disk: 7, diskTotal: 0 });
  });

  it('treats a container with no reported size as taking none', async () => {
    const { store } = await loaded([apiContainer({ sizeRootFsGb: null })]);

    expect(store.containers()[0].disk).toBe(0);
  });

  it('carries telemetry across a refresh, which only re-reads inventory', async () => {
    const { store } = await loaded([apiContainer()]);
    store.containers.update(cs => cs.map(c => ({ ...c, cpu: 42, cpuHist: [1, 2, 3] })));

    await store.refresh();

    expect(store.containers()[0]).toMatchObject({ cpu: 42, cpuHist: [1, 2, 3] });
  });

  it('answers byId, and null for one it does not hold', async () => {
    const { store } = await loaded([apiContainer()]);

    expect(store.byId('c-1')?.name).toBe('hermes-prod');
    expect(store.byId('c-missing')).toBeNull();
  });

  it('keeps the last inventory when a read fails', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockRejectedValue(new Error('daemon unreachable'));
    const { store } = await loaded([apiContainer()], { list });

    await store.refresh();

    expect(store.containers().map(c => c.id)).toEqual(['c-1']);
  });
});

describe('ContainerStore selection', () => {
  it('picks a container on the first read, so no page opens with nothing selected', async () => {
    const { store } = await loaded([apiContainer()]);

    expect(store.selected()?.name).toBe('hermes-prod');
  });

  it('follows a container that was recreated under a new id', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([apiContainer({ id: 'c-new' })]);
    const { store } = await loaded([apiContainer()], { list });

    await store.refresh();

    expect(store.selectedContainerId()).toBe('c-new');
  });

  it('keeps the selection through an empty read, which is usually a hiccup', async () => {
    const list = vi.fn().mockResolvedValueOnce([apiContainer()]).mockResolvedValue([]);
    const { store } = await loaded([apiContainer()], { list });

    await store.refresh();

    expect(store.selectedContainerId()).toBe('c-1');
  });

  it('leaves an explicit selection alone while it still exists', async () => {
    const { store } = await loaded([apiContainer(), apiContainer({ id: 'c-2' })]);
    store.select('c-2');

    await store.refresh();

    expect(store.selectedContainerId()).toBe('c-2');
  });

  it('tells the caches keyed to a container that the operator switched', async () => {
    const { store } = await loaded([apiContainer()]);
    const listener = vi.fn();
    store.onSelect(listener);

    store.select('c-1');

    expect(listener).toHaveBeenCalledWith('c-1');
  });
});

describe('ContainerStore telemetry', () => {
  const sample = (patch: Record<string, number> = {}) => ({
    cpuPercent: 12, ramMb: 512, ramTotalMb: 4096, rxBytes: 1_000, txBytes: 2_000,
    sampledAt: 1_000, ...patch,
  });

  it('folds a sample into the sparkline history', async () => {
    const stats = vi.fn().mockResolvedValue(sample());
    const { store } = await loaded([apiContainer()], { stats });

    await store.pollStats();

    expect(store.byId('c-1')).toMatchObject({
      cpu: 12, ram: 512, ramTotal: 4096, cpuHist: [12], ramHist: [512],
    });
  });

  it('reports no throughput on the first sample, having nothing to compare against', async () => {
    const stats = vi.fn().mockResolvedValue(sample());
    const { store } = await loaded([apiContainer()], { stats });

    await store.pollStats();

    expect(store.byId('c-1')).toMatchObject({ netIn: 0, netOut: 0 });
  });

  it('turns two byte counters into a rate over the elapsed time', async () => {
    const stats = vi.fn()
      .mockResolvedValueOnce(sample())
      .mockResolvedValue(sample({ rxBytes: 1_000 + 2_048, txBytes: 2_000 + 1_024, sampledAt: 3_000 }));
    const { store } = await loaded([apiContainer()], { stats });
    await store.pollStats();

    await store.pollStats();

    // 2048 bytes over 2s = 1 KiB/s in, 1024 over 2s = 0.5 KiB/s out
    expect(store.byId('c-1')).toMatchObject({ netIn: 1, netOut: 0.5 });
  });

  it('reads a counter that went backwards as no traffic, not as negative', async () => {
    const stats = vi.fn()
      .mockResolvedValueOnce(sample({ rxBytes: 10_000 }))
      .mockResolvedValue(sample({ rxBytes: 10, sampledAt: 3_000 }));
    const { store } = await loaded([apiContainer()], { stats });
    await store.pollStats();

    await store.pollStats();

    expect(store.byId('c-1')?.netIn).toBe(0);
  });

  it('samples only containers that are actually running', async () => {
    const stats = vi.fn().mockResolvedValue(sample());
    const { store } = await loaded([
      apiContainer(), apiContainer({ id: 'c-2', status: 'stopped' }),
      apiContainer({ id: 'c-3', status: 'unhealthy' }),
    ], { stats });

    await store.pollStats();

    expect(stats.mock.calls.map(c => c[1])).toEqual(['c-1', 'c-3']);
  });

  it('skips a tick rather than overlapping two fan-outs', async () => {
    const stats = vi.fn().mockReturnValue(new Promise(() => { /* never settles */ }));
    const { store } = await loaded([apiContainer()], { stats });

    void store.pollStats();
    await store.pollStats();

    expect(stats).toHaveBeenCalledTimes(1);
  });

  it('ignores a container that stopped between polls', async () => {
    const stats = vi.fn().mockRejectedValue(new Error('no such container'));
    const { store } = await loaded([apiContainer()], { stats });

    await store.pollStats();

    expect(store.byId('c-1')?.cpu).toBe(0);
  });
});

describe('ContainerStore fleet health', () => {
  const health = async (statuses: string[]) => {
    const { store } = await loaded(
      statuses.map((status, i) => apiContainer({ id: `c-${i}`, status: status as never })));
    return store.fleetHealth();
  };

  it('reports the worst state any container is in', async () => {
    expect(await health(['running', 'unhealthy'])).toBe('unhealthy');
    expect(await health(['stopped', 'running'])).toBe('running');
    expect(await health(['stopped', 'stopped'])).toBe('stopped');
  });

  it('reads an empty fleet as stopped rather than as healthy', async () => {
    expect(await health([])).toBe('stopped');
  });
});
