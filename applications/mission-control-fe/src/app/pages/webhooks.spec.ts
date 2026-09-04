import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { OutboundWebhook, WebhookListener, WebhookRoute } from '../core/models';
import { WebhooksPage } from './webhooks';
import { button, buttonWith, el, fill, press, settle, text, stubConfirm } from '../testing/dom';
import { provideStores } from '../testing/store';

const agents = [{ id: 'a-1', name: 'atlas' }, { id: 'a-2', name: 'scribe' }];

const route = (name: string, agentId: string, patch: Partial<WebhookRoute> = {}): WebhookRoute => ({
  name, description: `${name} hook`, url: `http://<agent-host>:8644/webhooks/${name}`,
  events: ['alert.firing'], prompt: 'Alert', skills: [], deliver: 'log', deliverOnly: false,
  secretMasked: '...Wjd0', createdAt: 1_000, agentId, containerId: 'c-1', ...patch,
});

const listener = (agentId: string, enabled = true): WebhookListener =>
  ({ agentId, enabled, host: '0.0.0.0', port: 8644, published: false });

const outboundTarget = (patch: Partial<OutboundWebhook> = {}): OutboundWebhook => ({
  index: 0, name: 'ci-notify', url: 'https://ci.example.test/hooks',
  events: ['on_session_end'], matcher: null, timeout: 10, secretEnv: 'CI_SECRET',
  literalSecret: false, agentId: 'a-1', containerId: 'c-1', ...patch,
});

/** Only what the page reaches for on the store. */
const storeStub = (
  routes: WebhookRoute[], listeners: WebhookListener[], outbound: OutboundWebhook[] = [],
) => ({
  agents: {
    forSelectedContainer: signal(agents),
    byId: (id: string) => agents.find(a => a.id === id) ?? null,
  },
  containers: {
    selected: signal({ id: 'c-1', name: 'hermes-prod' }),
  },
  webhooks: {
    forSelectedContainer: signal(routes),
    containerListeners: signal(listeners),
    listenerOf: (id: string) => listeners.find(l => l.agentId === id) ?? null,
    refresh: vi.fn().mockResolvedValue(undefined),
    setListenerEnabled: vi.fn().mockResolvedValue(true),
    subscribe: vi.fn().mockResolvedValue(true),
    remove: vi.fn().mockResolvedValue(true),
    secretOf: vi.fn().mockResolvedValue('the-real-secret'),
    test: vi.fn().mockResolvedValue('delivered 200'),
    outbound: signal(outbound),
    addOutbound: vi.fn().mockResolvedValue(true),
    updateOutbound: vi.fn().mockResolvedValue(true),
    removeOutbound: vi.fn().mockResolvedValue(true),
  },
});

/** Switches the page to its outbound half. */
const showOutbound = async (fixture: Parameters<typeof settle>[0]) => {
  press(fixture, 'outbound — the agent posts out');
  await settle(fixture);
};

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [...provideStores(store)] });
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

    expect(store.webhooks.refresh).toHaveBeenCalled();
  });

  it('re-reads the routes on its own clock while open, and stops when it closes', async () => {
    const { fixture, store } = render(storeStub([], []));
    const onOpen = store.webhooks.refresh.mock.calls.length;

    await vi.advanceTimersByTimeAsync(30_000);
    expect(store.webhooks.refresh.mock.calls.length).toBeGreaterThan(onOpen);

    fixture.destroy();
    const afterClose = store.webhooks.refresh.mock.calls.length;
    await vi.advanceTimersByTimeAsync(120_000);
    expect(store.webhooks.refresh.mock.calls.length).toBe(afterClose);
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

    expect(store.webhooks.secretOf).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).textContent).toContain('the-real-secret');

    press(fixture, 'hide', '.hook');
    expect(el(fixture).textContent).not.toContain('the-real-secret');
  });

  it('offers to turn on a listener that is off, and says routes cannot fire', () => {
    const { fixture, store } = render(
      storeStub([route('grafana', 'a-1')], [listener('a-1', false)]));

    expect(el(fixture).textContent).toContain('has no webhook listener');
    press(fixture, 'enable listener', '.listener-off');

    expect(store.webhooks.setListenerEnabled).toHaveBeenCalledWith('a-1', true);
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

    expect(store.webhooks.subscribe).toHaveBeenCalledWith('a-1', {
      name: 'grafana', prompt: 'Alert fired', description: undefined,
      events: ['alert.firing', 'alert.resolved'], deliver: undefined,
    });
  });

  it('will not create a route while the agent\'s listener is off, and says why', async () => {
    // hermes answers the subscribe with a setup walkthrough and exit 0; the backend now turns
    // that into a 409, and the page should not offer the click in the first place
    const { fixture, store } = render(storeStub([], [listener('a-1', false)]));
    press(fixture, '+ add webhook');
    await fill(fixture, 'route name', 'grafana');
    await settle(fixture);

    expect(el(fixture).textContent).toContain('has its listener off');
    const create = el(fixture).querySelector<HTMLButtonElement>('.form-actions .btn.primary')!;
    expect(create.disabled).toBe(true);
    create.click();
    expect(store.webhooks.subscribe).not.toHaveBeenCalled();
  });

  it('refuses a route with no name', async () => {
    const { fixture, store } = render(storeStub([], [listener('a-1')]));
    press(fixture, '+ add webhook');
    await settle(fixture);

    const create = el(fixture).querySelector<HTMLButtonElement>('.form-actions .btn.primary')!;
    expect(create.disabled).toBe(true);
    create.click();
    expect(store.webhooks.subscribe).not.toHaveBeenCalled();
  });

  it('keeps the form open when the write is refused', async () => {
    const store = storeStub([], [listener('a-1')]);
    store.webhooks.subscribe.mockResolvedValue(false);
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

    expect(store.webhooks.test).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).querySelector('.test-output')?.textContent).toContain('delivered 200');
  });

  it('asks before removing a route, because senders break silently', async () => {
    const { fixture, store } = render(storeStub([route('grafana', 'a-1')], [listener('a-1')]));
    const confirmed = stubConfirm(false);

    press(fixture, 'delete', '.hook');
    await settle(fixture);

    expect(confirmed).toHaveBeenCalled();
    expect(store.webhooks.remove).not.toHaveBeenCalled();

    confirmed.mockResolvedValue(true);
    press(fixture, 'delete', '.hook');
    await settle(fixture);
    expect(store.webhooks.remove).toHaveBeenCalledWith('a-1', 'grafana');
  });

  it('says so when there are no routes at all', () => {
    const { fixture } = render(storeStub([], []));

    expect(el(fixture).textContent).toContain('No webhooks in this container');
  });

  it('offers nothing to add when the container has no agents', () => {
    const store = storeStub([], []);
    store.agents.forSelectedContainer.set([]);
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

    expect(store.webhooks.secretOf).toHaveBeenCalledWith('a-1', 'grafana');
    expect(el(fixture).querySelector('code.secret')?.textContent).toBe('the-real-secret');
  });

  it('reveals one profile\'s secret only under that profile\'s row, not under a same-named route', async () => {
    // route names are per-profile namespaces, so two profiles can both hold `github`
    const { fixture, store } = render(storeStub(
      [route('github', 'a-1'), route('github', 'a-2')],
      [listener('a-1'), listener('a-2')]));

    press(fixture, 'reveal', '.hook');   // the first row — atlas's github
    await settle(fixture);

    expect(store.webhooks.secretOf).toHaveBeenCalledTimes(1);
    expect(store.webhooks.secretOf).toHaveBeenCalledWith('a-1', 'github');
    // scribe's same-named route keeps its mask — showing atlas's secret there hands the
    // operator the wrong signing key
    expect(el(fixture).querySelectorAll('code.secret')).toHaveLength(1);
  });

  it('shows a test result only on the route that was tested, not on a same-named one', async () => {
    const { fixture } = render(storeStub(
      [route('github', 'a-1'), route('github', 'a-2')],
      [listener('a-1'), listener('a-2')]));

    press(fixture, 'test', '.hook');
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.test-output')).toHaveLength(1);
  });

  it('keeps the mask when the secret could not be read', async () => {
    const store = storeStub([route('grafana', 'a-1')], [listener('a-1')]);
    store.webhooks.secretOf.mockResolvedValue(null);
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
    store.webhooks.test.mockResolvedValue('   ');
    const { fixture } = render(store);

    press(fixture, 'test');
    await settle(fixture);

    expect(el(fixture).querySelector('.test-output')?.textContent).toBe('no output');
  });

  it('shows nothing at all when the test itself could not be made', async () => {
    const store = storeStub([route('grafana', 'a-1')], [listener('a-1')]);
    store.webhooks.test.mockResolvedValue(null);
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


describe('WebhooksPage outbound targets', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('says an unsigned target is unsigned, because the receiver cannot tell who sent it', async () => {
    const { fixture } = render(storeStub([], [listener('a-1')],
      [outboundTarget({ secretEnv: null })]));

    await showOutbound(fixture);

    expect(text(fixture)).toContain('unsigned');
    expect(text(fixture)).toContain('cannot tell this POST came from your agent');
  });

  it('shows a hand-written inline secret as present without ever printing it', async () => {
    const { fixture } = render(storeStub([], [listener('a-1')],
      [outboundTarget({ secretEnv: null, literalSecret: true })]));

    await showOutbound(fixture);

    expect(text(fixture)).toContain('not shown, not touched');
    expect(text(fixture)).toContain('signed');
  });

  it('sends only the env var name, never a secret value', async () => {
    const { fixture, store } = render(storeStub([], [listener('a-1')]));

    await showOutbound(fixture);
    press(fixture, '+ add target');
    await fill(fixture, 'url', 'https://ci.example.test/hooks');
    press(fixture, 'on_session_end');
    await fill(fixture, 'signing secret', 'ci_secret');
    buttonWith(fixture, 'save target').click();
    await settle(fixture);

    expect(store.webhooks.addOutbound).toHaveBeenCalledWith('a-1', expect.objectContaining({
      url: 'https://ci.example.test/hooks',
      events: ['on_session_end'],
      secretEnv: 'CI_SECRET',
    }));
    expect(Object.keys(store.webhooks.addOutbound.mock.calls[0][1])).not.toContain('secret');
  });

  it('holds a save until at least one event is picked', async () => {
    const { fixture, store } = render(storeStub([], [listener('a-1')]));

    await showOutbound(fixture);
    press(fixture, '+ add target');
    await fill(fixture, 'url', 'https://ci.example.test/hooks');

    expect(button(fixture, 'save target').disabled).toBe(true);
    expect(store.webhooks.addOutbound).not.toHaveBeenCalled();
  });

  it('warns that a matcher does nothing for the events chosen', async () => {
    const { fixture } = render(storeStub([], [listener('a-1')]));

    await showOutbound(fixture);
    press(fixture, '+ add target');
    press(fixture, 'on_session_end');
    await fill(fixture, 'matcher', 'terminal');

    expect(text(fixture)).toContain('honours a matcher for');
  });

  it('flags an event this hermes does not know rather than rendering it as fine', async () => {
    const { fixture } = render(storeStub([], [listener('a-1')],
      [outboundTarget({ events: ['on_session_end', 'on_telepathy'] })]));

    await showOutbound(fixture);

    const chips = Array.from(el(fixture).querySelectorAll('.chip'))
      .filter(c => (c.textContent ?? '').trim() === 'on_telepathy');
    expect(chips).toHaveLength(1);
    expect(chips[0].classList.contains('off')).toBe(false);
  });

  it('says an edit lands on the next gateway restart', async () => {
    const { fixture } = render(storeStub([], [listener('a-1')], [outboundTarget()]));

    await showOutbound(fixture);

    expect(text(fixture)).toContain('next restart');
  });
});
