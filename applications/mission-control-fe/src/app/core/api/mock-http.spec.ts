import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesApi } from '../hermes-api';
import { MockHttp } from './mock-http';

const SOCKET = 'unix:///var/run/docker.sock';

/** The API clients on top of the fake backend — the same objects live mode uses. */
const api = () => new HermesApi('', new MockHttp(SOCKET));

/** A probe is deliberately not instantaneous, so let it land. */
const settle = () => vi.advanceTimersByTimeAsync(1_000);

describe('MockHttp', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    // a reachable remote daemon; the unreachable branch is asserted on its own
    vi.spyOn(Math, 'random').mockReturnValue(0.9);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('serves the local socket the deployment was configured with', async () => {
    const hosts = await api().hosts.list();

    expect(hosts).toEqual([expect.objectContaining({
      id: 'dh-local', kind: 'local', url: SOCKET, status: 'connected',
    })]);
  });

  it('adds a host, and the next read sees it — as a real backend would', async () => {
    const client = api();

    const added = client.hosts.add('remote', 'tcp://10.0.0.2:2375');
    await settle();

    expect(await added).toMatchObject({ name: 'remote', kind: 'remote', status: 'connected' });
    expect((await client.hosts.list()).map(h => h.name)).toEqual(['localhost', 'remote']);
  });

  it('takes a beat to answer a probe, so the UI can show it in flight', async () => {
    const client = api();
    let answered = false;
    void client.hosts.check('dh-local').then(() => { answered = true; });

    await vi.advanceTimersByTimeAsync(400);
    expect(answered).toBe(false);

    await vi.advanceTimersByTimeAsync(500);
    expect(answered).toBe(true);
  });

  it('reports an unreachable remote daemon rather than always succeeding', async () => {
    const client = api();
    const added = client.hosts.add('remote', 'tcp://10.0.0.2:2375');
    await settle();
    await added;

    vi.spyOn(Math, 'random').mockReturnValue(0.01);
    const remote = (await client.hosts.list()).find(h => h.kind === 'remote')!;
    const checked = client.hosts.check(remote.id);
    await settle();

    expect(await checked).toMatchObject({ status: 'error', engine: null });
    expect((await checked).note).toContain('connection refused');
  });

  it('keeps the local socket reachable, so the demo always has a daemon', async () => {
    vi.spyOn(Math, 'random').mockReturnValue(0.01);
    const client = api();

    const checked = client.hosts.check('dh-local');
    await settle();

    expect(await checked).toMatchObject({ status: 'connected' });
  });

  it('removes a host', async () => {
    const client = api();
    const added = client.hosts.add('remote', 'tcp://10.0.0.2:2375');
    await settle();
    const id = (await added).id;

    await client.hosts.remove(id);

    expect((await client.hosts.list()).map(h => h.id)).toEqual(['dh-local']);
  });

  it('refuses an id no host answers to', async () => {
    const client = api();

    // the probe delay applies to the failure too, so let the clock reach it
    const checked = expect(client.hosts.check('dh does not exist')).rejects.toThrow('no such host');
    await settle();
    await checked;

    await expect(client.hosts.remove('nope')).rejects.toThrow('no such host');
  });

  it('refuses a route it does not serve, rather than answering empty state', async () => {
    // a slice still carrying its own mock branch never reaches here; if one did,
    // this is what makes that visible instead of silently showing nothing
    await expect(api().containers.list()).rejects.toThrow('no route for GET /api/containers');
    await expect(api().health()).rejects.toThrow('no route for GET /health');
  });

  it('hands each read its own copy, so a caller cannot edit the backend\'s state', async () => {
    const client = api();
    const hosts = await client.hosts.list();
    hosts.pop();

    expect(await client.hosts.list()).toHaveLength(1);
  });
});
