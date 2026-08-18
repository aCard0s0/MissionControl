import { describe, expect, it, vi } from 'vitest';
import { DockerHost } from '../models';
import { HostStore } from './host-store';
import { StoreContext } from './store-context';

const host = (id: string, patch: Partial<DockerHost> = {}): DockerHost => ({
  id, name: id, url: `tcp://${id}:2375`, kind: 'remote', status: 'connected',
  engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 12, note: null, ...patch,
});

/** A context whose backend is a stub — `api` is the seam every slice shares. */
const context = (hosts: {
  list?: unknown; add?: unknown; check?: unknown; remove?: unknown;
}) => {
  const ctx = new StoreContext({ apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' });
  (ctx as unknown as { api: unknown }).api = { hosts };
  return ctx;
};

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('HostStore', () => {
  it('starts on a placeholder that says why it has nothing yet', () => {
    const store = new HostStore(context({}));

    expect(store.hosts()).toEqual([expect.objectContaining({
      id: 'dh-local', kind: 'local', status: 'disconnected',
      note: 'waiting for backend connection',
    })]);
    expect(store.overall()).toBe('disconnected');
  });

  it('replaces the placeholder with what the backend reported', async () => {
    const store = new HostStore(context({ list: vi.fn().mockResolvedValue([host('dh-local')]) }));

    await store.refresh();

    expect(store.hosts()).toEqual([expect.objectContaining({ id: 'dh-local' })]);
    expect(store.overall()).toBe('connected');
  });

  it('keeps the last inventory when a read fails, rather than blanking it', async () => {
    const list = vi.fn().mockResolvedValueOnce([host('dh-a')]).mockRejectedValue(new Error('offline'));
    const store = new HostStore(context({ list }));
    await store.refresh();

    await store.refresh();

    expect(store.hosts().map(h => h.id)).toEqual(['dh-a']);
  });

  it('re-reads the list after adding a host, since the backend assigns the id', async () => {
    const add = vi.fn().mockResolvedValue(host('dh-new'));
    const list = vi.fn().mockResolvedValue([host('dh-local'), host('dh-new')]);
    const store = new HostStore(context({ add, list }));

    store.add('remote', 'tcp://10.0.0.2:2375');
    await settle();

    expect(add).toHaveBeenCalledWith('remote', 'tcp://10.0.0.2:2375');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local', 'dh-new']);
  });

  it('toasts a failed add and leaves the inventory alone', async () => {
    const ctx = context({ add: vi.fn().mockRejectedValue(new Error('bad address')) });
    const store = new HostStore(ctx);

    store.add('remote', 'nonsense');
    await settle();

    expect(ctx.liveError()).toContain('add host failed: bad address');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('refuses to remove the local socket, which is not the operator\'s to delete', async () => {
    const remove = vi.fn();
    const store = new HostStore(context({ remove }));

    store.remove('dh-local');
    await settle();

    expect(remove).not.toHaveBeenCalled();
  });

  it('removes a remote host and re-reads what is left', async () => {
    const remove = vi.fn().mockResolvedValue(undefined);
    const list = vi.fn()
      .mockResolvedValueOnce([host('dh-local', { kind: 'local' }), host('dh-edge')])
      .mockResolvedValue([host('dh-local', { kind: 'local' })]);
    const store = new HostStore(context({ remove, list }));
    await store.refresh();

    store.remove('dh-edge');
    await settle();

    expect(remove).toHaveBeenCalledWith('dh-edge');
    expect(store.hosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('shows a check in flight, then what the daemon answered', async () => {
    const check = vi.fn().mockResolvedValue(host('dh-edge', { status: 'error', note: 'refused' }));
    const list = vi.fn().mockResolvedValue([host('dh-edge')]);
    const store = new HostStore(context({ check, list }));
    await store.refresh();

    store.check('dh-edge');
    expect(store.hosts()[0].status).toBe('connecting');
    expect(store.overall()).toBe('connecting');

    await settle();
    expect(store.hosts()[0]).toMatchObject({ status: 'error', note: 'refused' });
    expect(store.overall()).toBe('error');
  });

  it('toasts a failed check and re-reads the real state', async () => {
    const check = vi.fn().mockRejectedValue(new Error('timeout'));
    const list = vi.fn().mockResolvedValue([host('dh-edge')]);
    const ctx = context({ check, list });
    const store = new HostStore(ctx);
    await store.refresh();

    store.check('dh-edge');
    await settle();
    await settle();

    expect(ctx.liveError()).toContain('host check failed: timeout');
    expect(store.hosts()[0].status).toBe('connected');
  });
});
