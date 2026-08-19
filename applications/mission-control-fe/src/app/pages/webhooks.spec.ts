import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { WebhookListener, WebhookRoute } from '../core/models';
import { WebhooksPage } from './webhooks';
import { el, fill, press, settle, text } from '../testing/dom';

const agents = [{ id: 'a-1', name: 'atlas' }, { id: 'a-2', name: 'scribe' }];

const route = (name: string, agentId: string, patch: Partial<WebhookRoute> = {}): WebhookRoute => ({
  name, description: `${name} hook`, url: `http://<agent-host>:8644/webhooks/${name}`,
  events: ['alert.firing'], prompt: 'Alert', skills: [], deliver: 'log', deliverOnly: false,
  secretMasked: '...Wjd0', createdAt: 1_000, agentId, containerId: 'c-1', ...patch,
});

const listener = (agentId: string, enabled = true): WebhookListener =>
  ({ agentId, enabled, host: '0.0.0.0', port: 8644, published: false });

/** Only what the page reaches for on the store. */
const storeStub = (routes: WebhookRoute[], listeners: WebhookListener[]) => ({
  containerWebhooks: signal(routes),
  webhookListeners: signal(listeners),
  containerAgents: signal(agents),
  selectedContainer: signal({ id: 'c-1', name: 'hermes-prod' }),
  agentById: (id: string) => agents.find(a => a.id === id) ?? null,
  webhookListenerOf: (id: string) => listeners.find(l => l.agentId === id) ?? null,
  refreshWebhooks: vi.fn().mockResolvedValue(undefined),
  setWebhookListener: vi.fn().mockResolvedValue(true),
  addWebhook: vi.fn().mockResolvedValue(true),
  removeWebhook: vi.fn().mockResolvedValue(true),
  webhookSecret: vi.fn().mockResolvedValue('the-real-secret'),
  testWebhook: vi.fn().mockResolvedValue('delivered 200'),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(WebhooksPage);
  fixture.detectChanges();
  return { fixture, store };
};

describe('WebhooksPage', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reads the routes when it opens', () => {
    const { store } = render(storeStub([], []));

    expect(store.refreshWebhooks).toHaveBeenCalled();
  });

  it('shows each route with the endpoint a provider would post to', () => {
    const { fixture } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));

    expect(el(fixture).textContent).toContain('grafana');
    expect(el(fixture).textContent).toContain('http://<agent-host>:8644/webhooks/grafana');
    expect(el(fixture).textContent).toContain('alert.firing');
    expect(el(fixture).textContent).toContain('atlas');
  });

  it('says a route accepts everything when it filters no events', () => {
    const { fixture } = render(
      storeStub([route('grafana', 'a-1', { events: [] })], [listener('a-1')]));

    expect(el(fixture).textContent).toContain('all events');
  });

  it('shows only a masked secret until it is asked for', async () => {
    const { fixture, store } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));
    expect(el(fixture).textContent).toContain('...Wjd0');
    expect(el(fixture).textContent).not.toContain('the-real-secret');

    press(fixture, 'reveal', '.hook');
    await settle(fixture);

    expect(store.webhookSecret).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).textContent).toContain('the-real-secret');

    press(fixture, 'hide', '.hook');
    expect(el(fixture).textContent).not.toContain('the-real-secret');
  });

  it('offers to turn on a listener that is off, and says routes cannot fire', () => {
    const { fixture, store } = render(
      storeStub([route('grafana', 'a-1')], [listener('a-1', false)]));

    expect(el(fixture).textContent).toContain('has no webhook listener');
    press(fixture, 'enable listener', '.listener-off');

    expect(store.setWebhookListener).toHaveBeenCalledWith('a-1', true);
  });

  it('warns that nothing outside the docker network can reach a route yet', () => {
    const { fixture } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));

    expect(el(fixture).textContent).toContain('does not');
    expect(el(fixture).textContent).toContain('publish an agent port');
  });

  it('narrows the list to one agent', () => {
    const { fixture } = render(storeStub(
      [route('grafana', 'a-1'), route('github', 'a-2')],
      [listener('a-1'), listener('a-2')]));
    expect(el(fixture).querySelectorAll('.hook').length).toBe(2);

    press(fixture, '@scribe');

    expect(el(fixture).querySelectorAll('.hook').length).toBe(1);
    expect(el(fixture).textContent).toContain('github');
  });

  it('subscribes a route on the agent the form names', async () => {
    const { fixture, store } = render(storeStub([], [listener('a-1')]));
    press(fixture, '+ add webhook');

    await fill(fixture, 'route name', 'grafana');
    await fill(fixture, 'prompt', 'Alert fired');
    await fill(fixture, 'event filters', 'alert.firing, alert.resolved');
    press(fixture, 'create', '.form-actions');
    await settle(fixture);

    expect(store.addWebhook).toHaveBeenCalledWith('a-1', {
      name: 'grafana', prompt: 'Alert fired', description: undefined,
      events: ['alert.firing', 'alert.resolved'], deliver: undefined,
    });
  });

  it('refuses a route with no name', async () => {
    const { fixture, store } = render(storeStub([], [listener('a-1')]));
    press(fixture, '+ add webhook');
    await settle(fixture);

    const create = el(fixture).querySelector<HTMLButtonElement>('.form-actions .btn.primary')!;
    expect(create.disabled).toBe(true);
    create.click();
    expect(store.addWebhook).not.toHaveBeenCalled();
  });

  it('keeps the form open when the write is refused', async () => {
    const store = storeStub([], [listener('a-1')]);
    store.addWebhook.mockResolvedValue(false);
    const { fixture } = render(store);
    press(fixture, '+ add webhook');
    await fill(fixture, 'route name', 'grafana');

    press(fixture, 'create', '.form-actions');
    await settle(fixture);

    expect(el(fixture).querySelector('.form-panel')).not.toBeNull();
  });

  it('shows what a test fire printed', async () => {
    const { fixture, store } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));

    press(fixture, 'test', '.hook');
    await settle(fixture);

    expect(store.testWebhook).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).querySelector('.test-output')?.textContent).toContain('delivered 200');
  });

  it('asks before removing a route, because senders break silently', async () => {
    const confirmed = vi.spyOn(window, 'confirm').mockReturnValue(false);
    const { fixture, store } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));

    press(fixture, 'remove', '.hook');
    await settle(fixture);

    expect(confirmed).toHaveBeenCalled();
    expect(store.removeWebhook).not.toHaveBeenCalled();

    confirmed.mockReturnValue(true);
    press(fixture, 'remove', '.hook');
    await settle(fixture);
    expect(store.removeWebhook).toHaveBeenCalledWith('a-1', 'grafana');
    confirmed.mockRestore();
  });

  it('says so when there are no routes at all', () => {
    const { fixture } = render(storeStub([], []));

    expect(el(fixture).textContent).toContain('No webhooks in this container');
  });

  it('offers nothing to add when the container has no agents', () => {
    const store = storeStub([], []);
    store.containerAgents.set([]);
    const { fixture } = render(store);

    expect(el(fixture).querySelector<HTMLButtonElement>('.page-head .btn')!.disabled).toBe(true);
  });
});

describe('WebhooksPage route details', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('reveals the full signing secret only when asked', async () => {
    const { fixture, store } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));
    expect(text(fixture)).toContain('...Wjd0');

    press(fixture, 'reveal');
    await settle(fixture);

    expect(store.webhookSecret).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).querySelector('code.secret')?.textContent).toBe('the-real-secret');
  });

  it('keeps the mask when the secret could not be read', async () => {
    const store = storeStub([route('grafana', 'a-1')], [listener('a-1')]);
    store.webhookSecret.mockResolvedValue(null);
    const { fixture } = render(store);

    press(fixture, 'reveal');
    await settle(fixture);

    expect(el(fixture).querySelector('code.secret')).toBeNull();
    expect(text(fixture)).toContain('...Wjd0');
  });

  it('shows what a test delivery printed', async () => {
    const { fixture } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));

    press(fixture, 'test');
    await settle(fixture);

    expect(el(fixture).querySelector('.test-output')?.textContent).toBe('delivered 200');
  });

  it('says so rather than showing a blank pane when a test printed nothing', async () => {
    const store = storeStub([route('grafana', 'a-1')], [listener('a-1')]);
    store.testWebhook.mockResolvedValue('   ');
    const { fixture } = render(store);

    press(fixture, 'test');
    await settle(fixture);

    expect(el(fixture).querySelector('.test-output')?.textContent).toBe('no output');
  });

  it('shows nothing at all when the test itself could not be made', async () => {
    const store = storeStub([route('grafana', 'a-1')], [listener('a-1')]);
    store.testWebhook.mockResolvedValue(null);
    const { fixture } = render(store);

    press(fixture, 'test');
    await settle(fixture);

    expect(el(fixture).querySelector('.test-output')).toBeNull();
  });

  it('marks a route whose owning profile is gone rather than rendering a blank name', () => {
    const { fixture } = render(storeStub([route('orphan', 'a-deleted')], []));

    expect(text(fixture)).toContain('?');
  });
});
