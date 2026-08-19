import { describe, expect, it, vi } from 'vitest';
import { ApiWebhookSubscription } from '../hermes-api';
import { apiProfile, loadedAgentSlices } from '../../testing/store';

const route = (name: string, patch: Partial<ApiWebhookSubscription> = {}): ApiWebhookSubscription => ({
  name, description: `${name} hook`, url: `http://<agent-host>:8644/webhooks/${name}`,
  events: ['alert.firing'], prompt: 'Alert', skills: [], deliver: 'log', deliverOnly: false,
  secretMasked: '...Wjd0', createdAt: 1_000, ...patch,
});

const answer = (
  subscriptions: ApiWebhookSubscription[], platform: Partial<{
    enabled: boolean; host: string | null; port: number | null; published: boolean;
  }> = {},
) => ({
  subscriptions,
  platform: { enabled: true, host: '0.0.0.0', port: 8644, published: false, ...platform },
});

/** A store holding one container and its profiles, with the webhook API stubbed. */
const loaded = async (webhooks: Record<string, unknown>, profiles = ['atlas', 'scribe']) => {
  const slices = await loadedAgentSlices(
    { agents: { webhooks } },
    { profiles: profiles.map(name => apiProfile(name)) });
  return {
    ...slices,
    store: slices.webhooks,
  };
};

describe('WebhookStore listing', () => {
  it('unions the routes of every profile in the container', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([route('grafana')]))
      .mockResolvedValueOnce(answer([route('github')]));
    const { store } = await loaded({ list });

    await store.refresh();

    expect(store.routes().map(r => r.name)).toEqual(['grafana', 'github']);
    // each route knows the profile that owns it, which is how a delete is addressed
    expect(store.routes().map(r => r.agentId)).toEqual(['a-atlas', 'a-scribe']);
  });

  it('records each profile\'s listener separately', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([], { enabled: true }))
      .mockResolvedValueOnce(answer([], { enabled: false }));
    const { store } = await loaded({ list });

    await store.refresh();

    expect(store.listenerOf('a-atlas')?.enabled).toBe(true);
    expect(store.listenerOf('a-scribe')?.enabled).toBe(false);
  });

  it('never reports a route as reachable, because no agent port is published', async () => {
    const list = vi.fn().mockResolvedValue(answer([route('grafana')]));
    const { store } = await loaded({ list });

    await store.refresh();

    expect(store.listeners().every(l => !l.published)).toBe(true);
  });

  it('keeps the rest of the list when one profile cannot be read', async () => {
    const list = vi.fn()
      .mockRejectedValueOnce(new Error('container stopped'))
      .mockResolvedValueOnce(answer([route('github')]));
    const { store } = await loaded({ list });

    await store.refresh();

    expect(store.routes().map(r => r.name)).toEqual(['github']);
  });

  it('holds nothing when the container has no profiles', async () => {
    const list = vi.fn();
    const { store } = await loaded({ list }, []);

    await store.refresh();

    expect(store.routes()).toEqual([]);
    expect(store.listeners()).toEqual([]);
    expect(list).not.toHaveBeenCalled();
  });

  it('carries only a masked secret', async () => {
    const list = vi.fn().mockResolvedValue(answer([route('grafana')]));
    const { store } = await loaded({ list }, ['atlas']);

    await store.refresh();

    expect(store.routes()[0].secretMasked).toBe('...Wjd0');
    expect(JSON.stringify(store.routes())).not.toContain('FfrK');
  });
});

describe('WebhookStore mutations', () => {
  it('turns a profile\'s listener on', async () => {
    const setPlatform = vi.fn().mockResolvedValue(answer([], { enabled: true }));
    const { store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([], { enabled: false })), setPlatform,
    }, ['atlas']);
    await store.refresh();

    expect(await store.setListenerEnabled('a-atlas', true)).toBe(true);

    expect(setPlatform).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' }, true, undefined, undefined);
    expect(store.listenerOf('a-atlas')?.enabled).toBe(true);
  });

  it('subscribes a route on the profile it was assigned to', async () => {
    const subscribe = vi.fn().mockResolvedValue(answer([route('grafana')]));
    const { store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([])), subscribe,
    }, ['atlas']);

    expect(await store.subscribe('a-atlas', { name: 'grafana', prompt: 'Alert' })).toBe(true);

    expect(subscribe).toHaveBeenCalledWith(expect.anything(), { name: 'grafana', prompt: 'Alert' });
    expect(store.routes().map(r => r.name)).toEqual(['grafana']);
  });

  it('replaces only the owning profile\'s routes with what a write answered', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([route('atlas-hook')]))
      .mockResolvedValueOnce(answer([route('scribe-hook')]));
    const remove = vi.fn().mockResolvedValue(answer([]));
    const { store } = await loaded({ list, remove });
    await store.refresh();

    await store.remove('a-atlas', 'atlas-hook');

    expect(store.routes().map(r => r.name)).toEqual(['scribe-hook']);
  });

  it('reads the full secret only when asked', async () => {
    const secret = vi.fn().mockResolvedValue({ secret: 'the-real-secret' });
    const { store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([route('grafana')])), secret,
    }, ['atlas']);

    expect(await store.secretOf('a-atlas', 'grafana')).toBe('the-real-secret');
    expect(secret).toHaveBeenCalledWith(expect.anything(), 'grafana');
  });

  it('answers null and says why when a secret cannot be read', async () => {
    const secret = vi.fn().mockRejectedValue(new Error('no such route'));
    const { ctx, store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([])), secret,
    }, ['atlas']);

    expect(await store.secretOf('a-atlas', 'gone')).toBeNull();
    expect(ctx.liveError()).toContain('read webhook secret failed: no such route');
  });

  it('answers with whatever hermes printed for a test fire', async () => {
    const test = vi.fn().mockResolvedValue({ output: 'delivered 200' });
    const { store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([route('grafana')])), test,
    }, ['atlas']);

    expect(await store.test('a-atlas', 'grafana')).toBe('delivered 200');
  });

  it('reports a refused write and leaves the list alone', async () => {
    const remove = vi.fn().mockRejectedValue(new Error('route is in use'));
    const { ctx, store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([route('grafana')])), remove,
    }, ['atlas']);
    await store.refresh();

    expect(await store.remove('a-atlas', 'grafana')).toBe(false);
    expect(ctx.liveError()).toContain('remove webhook failed: route is in use');
    expect(store.routes().map(r => r.name)).toEqual(['grafana']);
  });

  it('does nothing for a profile it does not hold', async () => {
    const remove = vi.fn();
    const { store } = await loaded({ list: vi.fn().mockResolvedValue(answer([])), remove });

    expect(await store.remove('a-gone', 'grafana')).toBe(false);
    expect(await store.secretOf('a-gone', 'grafana')).toBeNull();
    expect(remove).not.toHaveBeenCalled();
  });

  it('drops the routes and listener of a profile that is gone', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([route('atlas-hook')]))
      .mockResolvedValueOnce(answer([route('scribe-hook')]));
    const { store } = await loaded({ list });
    await store.refresh();

    store.dropByAgent('a-atlas');

    expect(store.routes().map(r => r.name)).toEqual(['scribe-hook']);
    expect(store.listenerOf('a-atlas')).toBeNull();

    store.dropByAgents(new Set(['a-scribe']));
    expect(store.routes()).toEqual([]);
    expect(store.listeners()).toEqual([]);
  });
});

describe('WebhookStore listeners', () => {
  it('shows only the listeners of the selected container\'s profiles', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([route('grafana')]))
      .mockResolvedValueOnce(answer([route('github')], { port: 8645 }));
    const { store, containers } = await loaded({ list });
    await store.refresh();

    expect(store.containerListeners().map(l => l.port)).toEqual([8644, 8645]);

    containers.select('c-2');                  // a container with no profiles of its own
    expect(store.containerListeners()).toEqual([]);
    expect(store.forSelectedContainer()).toEqual([]);
  });

  it('answers what hermes printed for a test delivery', async () => {
    const { store } = await loaded({
      list: vi.fn().mockResolvedValue(answer([route('grafana')])),
      test: vi.fn().mockResolvedValue({ output: 'delivered 200' }),
    });
    await store.refresh();

    expect(await store.test('a-atlas', 'grafana')).toBe('delivered 200');
  });

  it('says why a test delivery could not be made', async () => {
    const { store, ctx } = await loaded({
      list: vi.fn().mockResolvedValue(answer([route('grafana')])),
      test: vi.fn().mockRejectedValue(new Error('listener is off')),
    });
    await store.refresh();

    expect(await store.test('a-atlas', 'grafana')).toBeNull();
    expect(ctx.liveError()).toBe('webhook test failed: listener is off');
  });

  it('does not test on behalf of a profile it does not hold', async () => {
    const test = vi.fn();
    const { store } = await loaded({ list: vi.fn().mockResolvedValue(answer([])), test });

    expect(await store.test('a-gone', 'grafana')).toBeNull();
    expect(test).not.toHaveBeenCalled();
  });
});
