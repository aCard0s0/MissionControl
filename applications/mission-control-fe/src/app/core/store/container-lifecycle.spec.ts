import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContainerLifecycle } from './container-lifecycle';
import { ContainerStore } from './container-store';
import { HostStore } from './host-store';
import { ImageCatalogStore } from './image-catalog-store';
import { apiContainer, testContext } from '../../testing/store';

/** The three slices a lifecycle action touches, sharing one stubbed backend. */
const loaded = async (containersApi: Record<string, unknown>, images: Record<string, unknown> = {}) => {
  const ctx = testContext();
  const containers = new ContainerStore(ctx);
  const hosts = new HostStore(ctx);
  const imageStore = new ImageCatalogStore(ctx, containers, hosts);
  (ctx as unknown as { api: unknown }).api = {
    containers: { list: vi.fn().mockResolvedValue([apiContainer()]), ...containersApi, ...images },
  };
  await containers.refresh();
  containers.select('c-1');
  return { ctx, containers, images: imageStore, lifecycle: new ContainerLifecycle(ctx, containers, imageStore) };
};

describe('ContainerLifecycle deploy', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('resolves only once the refreshed inventory holds the new container', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([apiContainer(), apiContainer({ id: 'c-2', name: 'hermes-lab' })]);
    const { lifecycle, containers } = await loaded({
      list, deploy: vi.fn().mockResolvedValue({ id: 'c-2' }),
    });

    const deployed = lifecycle.deploy('hermes-lab', 'v2026.8.3', ['ops'], 'dh-local');
    await vi.advanceTimersByTimeAsync(600);

    expect(await deployed).toBe('c-2');
    expect(containers.byId('c-2')).not.toBeNull();
    expect(containers.selectedContainerId()).toBe('c-2');
  });

  it('defaults to the local daemon when no host is named', async () => {
    const deploy = vi.fn().mockResolvedValue({ id: 'c-2' });
    const { lifecycle } = await loaded({ deploy });

    const deployed = lifecycle.deploy('hermes-lab', 'v1', []);
    await vi.advanceTimersByTimeAsync(600);
    await deployed;

    expect(deploy).toHaveBeenCalledWith('dh-local', 'hermes-lab', 'v1', []);
  });

  it('answers an empty id and says why a deploy failed', async () => {
    const { lifecycle, ctx } = await loaded({
      deploy: vi.fn().mockRejectedValue(new Error('name already in use')),
    });

    expect(await lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local')).toBe('');
    expect(ctx.liveError()).toBe('deploy failed: name already in use');
  });
});

describe('ContainerLifecycle start and stop', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('asks the daemon to start, then re-reads what actually happened', async () => {
    const start = vi.fn().mockResolvedValue(undefined);
    const list = vi.fn().mockResolvedValue([apiContainer({ status: 'running' })]);
    const { lifecycle } = await loaded({ start, list });

    lifecycle.setStatus('c-1', 'running');
    await vi.advanceTimersByTimeAsync(700);

    expect(start).toHaveBeenCalledWith('dh-local', 'c-1');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('asks the daemon to stop for any state that is not running', async () => {
    const stop = vi.fn().mockResolvedValue(undefined);
    const { lifecycle } = await loaded({ stop });

    lifecycle.setStatus('c-1', 'stopped');
    await vi.advanceTimersByTimeAsync(700);

    expect(stop).toHaveBeenCalledWith('dh-local', 'c-1');
  });

  it('names the verb that failed, not a generic error', async () => {
    const { lifecycle, ctx } = await loaded({
      start: vi.fn().mockRejectedValue(new Error('port bound')),
    });

    lifecycle.setStatus('c-1', 'running');
    await vi.advanceTimersByTimeAsync(0);

    expect(ctx.liveError()).toBe('start failed: port bound');
  });

  it('says so rather than going quiet on a container it does not hold', async () => {
    const start = vi.fn();
    const { lifecycle, ctx } = await loaded({ start });

    lifecycle.setStatus('c-missing', 'running');

    expect(start).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBe('container is no longer available');
  });
});

describe('ContainerLifecycle update', () => {
  it('follows the selection onto the replacement, whose id is new', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([apiContainer({ id: 'c-new', version: 'v2026.8.4' })]);
    const { lifecycle, containers } = await loaded({
      list, update: vi.fn().mockResolvedValue({ id: 'c-new' }),
      imageTags: vi.fn().mockResolvedValue({ repository: 'r', tags: [] }),
    });

    expect(await lifecycle.update('c-1', 'v2026.8.4')).toBe('c-new');
    expect(containers.selectedContainerId()).toBe('c-new');
  });

  it('leaves the selection alone when the updated container was not the selected one', async () => {
    const list = vi.fn().mockResolvedValue([
      apiContainer(), apiContainer({ id: 'c-2', name: 'hermes-lab' })]);
    const { lifecycle, containers } = await loaded({
      list, update: vi.fn().mockResolvedValue({ id: 'c-2-new' }),
      imageTags: vi.fn().mockResolvedValue({ repository: 'r', tags: [] }),
    });
    await containers.refresh();

    await lifecycle.update('c-2', 'v2026.8.4');

    expect(containers.selectedContainerId()).toBe('c-1');
  });

  it('refuses an update that would be a no-op, quietly — nothing failed', async () => {
    const update = vi.fn();
    const { lifecycle, ctx } = await loaded({ update });

    expect(await lifecycle.update('c-1', 'v2026.8.3')).toBe('');   // already on this tag
    expect(await lifecycle.update('c-1', '')).toBe('');
    expect(update).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBeNull();
  });

  it('says so when the container to update is no longer there', async () => {
    const update = vi.fn();
    const { lifecycle, ctx } = await loaded({ update });

    expect(await lifecycle.update('c-missing', 'v2026.8.4')).toBe('');
    expect(update).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBe('container is no longer available');
  });

  it('re-reads the inventory after a failed update, which may have half landed', async () => {
    const list = vi.fn().mockResolvedValue([apiContainer()]);
    const { lifecycle, ctx } = await loaded({
      list, update: vi.fn().mockRejectedValue(new Error('pull timed out')),
    });

    expect(await lifecycle.update('c-1', 'v2026.8.4')).toBe('');
    expect(ctx.liveError()).toBe('update failed: pull timed out');
    expect(list).toHaveBeenCalledTimes(2);
  });
});

describe('ContainerLifecycle remove', () => {
  it('clears the selection when the removed container was the selected one', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([]);
    const { lifecycle, containers } = await loaded({
      list, remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await lifecycle.remove('c-1')).toBe(true);
    expect(containers.selectedContainerId()).toBe('');
  });

  it('re-reads after a failure, because the delete may have got past the container', async () => {
    const list = vi.fn().mockResolvedValue([apiContainer()]);
    const { lifecycle, ctx, containers } = await loaded({
      list, remove: vi.fn().mockRejectedValue(new Error('volume busy')),
    });

    expect(await lifecycle.remove('c-1')).toBe(false);
    expect(containers.byId('c-1')).not.toBeNull();
    expect(ctx.liveError()).toBe('remove failed: volume busy');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('says so rather than going quiet on a container it does not hold', async () => {
    const remove = vi.fn();
    const { lifecycle, ctx } = await loaded({ remove });

    expect(await lifecycle.remove('c-missing')).toBe(false);
    expect(remove).not.toHaveBeenCalled();
    expect(ctx.liveError()).toBe('container is no longer available');
  });
});
