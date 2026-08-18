import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../hermes-store';

/**
 * The first slice served by {@link MockHttp}: it has one code path, so this
 * drives it through the real store in mock data mode and every call lands on the
 * fake backend the way it would on a real one.
 */
const store = () => new HermesStore();

/** Lets the fake backend's probe delay land. */
const settle = () => vi.advanceTimersByTimeAsync(1_000);

describe('HostStore', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(Math, 'random').mockReturnValue(0.9);   // a reachable remote daemon
    window.__MC_CONFIG__ = {
      dataMode: 'mock', apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
    };
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('shows the local socket as soon as the backend answers', async () => {
    const s = store();
    // before the first read, the placeholder says why it has nothing yet
    expect(s.dockerHosts()[0]).toMatchObject({ id: 'dh-local', status: 'disconnected' });

    await settle();

    expect(s.dockerHosts()).toEqual([expect.objectContaining({
      id: 'dh-local', status: 'connected', engine: 'Docker 27.3',
    })]);
    expect(s.dockerOverall()).toBe('connected');
  });

  it('adds a host and re-reads the list the backend now holds', async () => {
    const s = store();
    await settle();

    s.addDockerHost('remote', 'tcp://10.0.0.2:2375');
    await settle();

    expect(s.dockerHosts().map(h => h.name)).toEqual(['localhost', 'remote']);
    expect(s.dockerHosts()[1]).toMatchObject({ kind: 'remote', status: 'connected' });
  });

  it('removes a host it added', async () => {
    const s = store();
    await settle();
    s.addDockerHost('remote', 'tcp://10.0.0.2:2375');
    await settle();
    const remote = s.dockerHosts().find(h => h.kind === 'remote')!;

    s.removeDockerHost(remote.id);
    await settle();

    expect(s.dockerHosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('refuses to remove the local socket, which is not the operator\'s to delete', async () => {
    const s = store();
    await settle();

    s.removeDockerHost('dh-local');
    await settle();

    expect(s.dockerHosts().map(h => h.id)).toEqual(['dh-local']);
  });

  it('shows a check in flight, then what the daemon answered', async () => {
    const s = store();
    await settle();

    s.checkDockerHost('dh-local');
    expect(s.dockerHosts()[0].status).toBe('connecting');
    expect(s.dockerOverall()).toBe('connecting');

    await settle();
    expect(s.dockerHosts()[0].status).toBe('connected');
  });

  it('reports an unreachable daemon on the row rather than as a toast', async () => {
    const s = store();
    await settle();
    s.addDockerHost('remote', 'tcp://10.0.0.2:2375');
    await settle();
    const remote = s.dockerHosts().find(h => h.kind === 'remote')!;

    vi.spyOn(Math, 'random').mockReturnValue(0.01);
    s.checkDockerHost(remote.id);
    await settle();

    const checked = s.dockerHosts().find(h => h.id === remote.id)!;
    expect(checked.status).toBe('error');
    expect(checked.note).toContain('connection refused');
    expect(s.dockerOverall()).toBe('error');
    expect(s.liveError()).toBeNull();
  });

  it('toasts when the call itself fails, and re-reads what is really there', async () => {
    const s = store();
    await settle();

    s.checkDockerHost('dh-gone');
    await settle();

    expect(s.liveError()).toContain('host check failed');
    expect(s.dockerHosts().map(h => h.id)).toEqual(['dh-local']);
  });
});
