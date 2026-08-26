import { describe, expect, it, vi } from 'vitest';
import { DockerHost } from '../models';
import { dockerHost } from '../../testing/models';
import { flush, liveError, testSlices } from '../../testing/store';

const host = (id: string, patch: Partial<DockerHost> = {}): DockerHost =>
  dockerHost(id, { engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 12, ...patch });

const built = (hosts: {
  list?: unknown; add?: unknown; check?: unknown; remove?: unknown;
}) => testSlices({ hosts });

describe('HostStore', () => {
  it('starts on a placeholder that says why it has nothing yet', () => {
    const store = built({}).hosts;

    expect(store.hosts()).toEqual([expect.objectContaining({
      id: 'dh-local', kind: 'local', status: 'disconnected',
      note: 'waiting for backend connection',
    })]);
    expect(store.overall()).toBe('disconnected');
  });

  it('replaces the placeholder with what the backend reported', async () => {
    const store = built({ list: vi.fn().mockResolvedValue([host('dh-local')]) }).hosts;

    await store.refresh();

    expect(store.hosts()).toEqual([expect.objectContaining({ id: 'dh-local' })]);
    expect(store.overall()).toBe('connected');
  });

  it('keeps the last inventory when a read fails, rather than blanking it', async () => {
    const list = vi.fn().mockResolvedValueOnce([host('dh-a')]).mockRejectedValue(new Error('offline'));
    const store = built({ list }).hosts;
    await store.refresh();

    await store.refresh();

    expect(store.hosts().map(h => h.id)).toEqual(['dh-a']);
  });

  it('re-reads the list after adding a host, since the backend assigns the id', async () => {
    const add = vi.fn().mockResolvedValue(host('dh-new'));
    const list = vi.fn().mockResolvedValue([host('dh-local'), host('dh-new')]);
    const store = built({ add, list }).hosts;

    store.add('remote', 'tcp://10.0.0.2:2375');
    await flush();

    expect(add).toHaveBeenCalledWith('remote', 'tcp://10.0.0.2:2375');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local', 'dh-new']);
  });

  it('toasts a failed add and leaves the inventory alone', async () => {
    const { ctx, hosts: store } = built({ add: vi.fn().mockRejectedValue(new Error('bad address')) });

    store.add('remote', 'nonsense');
    await flush();

    expect(liveError(ctx)).toContain('add host failed: bad address');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('refuses to remove the local socket, which is not the operator\'s to delete', async () => {
    const remove = vi.fn();
    const store = built({ remove }).hosts;

    store.remove('dh-local');
    await flush();

    expect(remove).not.toHaveBeenCalled();
  });

  it('removes a remote host and re-reads what is left', async () => {
    const remove = vi.fn().mockResolvedValue(undefined);
    const list = vi.fn()
      .mockResolvedValueOnce([host('dh-local', { kind: 'local' }), host('dh-edge')])
      .mockResolvedValue([host('dh-local', { kind: 'local' })]);
    const store = built({ remove, list }).hosts;
    await store.refresh();

    store.remove('dh-edge');
    await flush();

    expect(remove).toHaveBeenCalledWith('dh-edge');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('shows a check in flight, then what the daemon answered', async () => {
    const check = vi.fn().mockResolvedValue(host('dh-edge', { status: 'error', note: 'refused' }));
    const list = vi.fn().mockResolvedValue([host('dh-edge')]);
    const store = built({ check, list }).hosts;
    await store.refresh();

    store.check('dh-edge');
    expect(store.hosts()[0].status).toBe('connecting');
    expect(store.overall()).toBe('connecting');

    await flush();
    expect(store.hosts()[0]).toMatchObject({ status: 'error', note: 'refused' });
    expect(store.overall()).toBe('error');
  });

  it('toasts a failed check and re-reads the real state', async () => {
    const check = vi.fn().mockRejectedValue(new Error('timeout'));
    const list = vi.fn().mockResolvedValue([host('dh-edge')]);
    const { ctx, hosts: store } = built({ check, list });
    await store.refresh();

    store.check('dh-edge');
    await flush();
    await flush();

    expect(liveError(ctx)).toContain('host check failed: timeout');
    expect(store.hosts()[0].status).toBe('connected');
  });
});
