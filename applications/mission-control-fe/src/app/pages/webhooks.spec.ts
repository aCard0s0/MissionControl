import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { Webhook } from '../core/models';
import { WebhooksPage } from './webhooks';

const hook = (id: string, agentId: string, patch: Partial<Webhook> = {}): Webhook => ({
  id, agentId, name: `hook ${id}`, slug: `/hooks/${id}`, secretMasked: '…abcd',
  events: ['*'], active: true, deliveries: [], ...patch,
});

const agents = [{ id: 'a-1', name: 'atlas' }, { id: 'a-2', name: 'scribe' }];

const storeStub = (hooks: Webhook[]) => ({
  containerWebhooks: signal(hooks),
  containerAgents: signal(agents),
  selectedContainer: signal({ id: 'c-1', name: 'hermes-prod' }),
  agentById: (id: string) => agents.find(a => a.id === id) ?? null,
  addWebhook: vi.fn(),
  toggleWebhook: vi.fn(),
  removeWebhook: vi.fn(),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(WebhooksPage);
  fixture.detectChanges();
  return { fixture, store };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void; whenStable(): Promise<unknown> };

const press = (fixture: Fixture, label: string, within?: string): void => {
  const scope = within ? el(fixture).querySelector(within) : el(fixture);
  if (!scope) throw new Error(`no element matching "${within}"`);
  const match = Array.from(scope.querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim() === label);
  if (!match) throw new Error(`no button labelled "${label}"`);
  (match as HTMLButtonElement).click();
  fixture.detectChanges();
};

/** The `.field` whose label starts with this text. */
const field = (fixture: Fixture, label: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.field'))
    .find(f => (f.querySelector('label')?.textContent ?? '').trim().toLowerCase()
      .startsWith(label.toLowerCase()));
  if (!match) throw new Error(`no field labelled "${label}"`);
  return match;
};

const fill = async (fixture: Fixture, label: string, value: string): Promise<void> => {
  const input = field(fixture, label).querySelector<HTMLInputElement>('.input')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  await fixture.whenStable();
  fixture.detectChanges();
};

describe('WebhooksPage list', () => {
  it('shows each hook with its endpoint, secret and events', () => {
    const { fixture } = render(storeStub([hook('h-1', 'a-1', { events: ['alert.firing'] })]));

    expect(el(fixture).textContent).toContain('hook h-1');
    expect(el(fixture).textContent).toContain('https://gateway.local/hooks/h-1');
    expect(el(fixture).textContent).toContain('…abcd');
    expect(el(fixture).textContent).toContain('alert.firing');
    expect(el(fixture).textContent).toContain('atlas');
  });

  it('says a hook has never fired rather than leaving the panel blank', () => {
    const { fixture } = render(storeStub([hook('h-1', 'a-1')]));

    expect(el(fixture).textContent).toContain('no deliveries yet');
  });

  it('lists deliveries newest-label first, with the status the gateway reported', () => {
    const { fixture } = render(storeStub([hook('h-1', 'a-1', {
      deliveries: [{ ts: 1, event: 'alert.firing', status: 'fail', code: 502 }],
    })]));

    expect(el(fixture).textContent).toContain('alert.firing');
    expect(el(fixture).textContent).toContain('HTTP 502');
  });

  it('narrows the list to one agent, and says which filter is empty', () => {
    const { fixture } = render(storeStub([hook('h-1', 'a-1'), hook('h-2', 'a-2')]));
    expect(el(fixture).querySelectorAll('.hook').length).toBe(2);

    press(fixture, '@scribe');
    expect(el(fixture).querySelectorAll('.hook').length).toBe(1);
    expect(el(fixture).textContent).toContain('hook h-2');

    press(fixture, '@atlas');
    expect(el(fixture).textContent).toContain('hook h-1');
  });

  it('says so when the container has no hooks at all', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No webhooks in this container');
  });

  it('sends enable, disable and remove to the store', () => {
    const { fixture, store } = render(storeStub([hook('h-1', 'a-1', { active: true })]));

    press(fixture, 'disable', '.hook');
    expect(store.toggleWebhook).toHaveBeenCalledWith('h-1');

    press(fixture, 'remove', '.hook');
    expect(store.removeWebhook).toHaveBeenCalledWith('h-1');
  });
});

describe('WebhooksPage add form', () => {
  it('starts on the agent the list is filtered to', async () => {
    const { fixture } = render(storeStub([]));
    press(fixture, '@scribe');

    press(fixture, '+ add webhook');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(field(fixture, 'agent').querySelector<HTMLSelectElement>('.select')!.value).toBe('a-2');
  });

  it('falls back to the first agent when no filter is set', async () => {
    const { fixture } = render(storeStub([]));

    press(fixture, '+ add webhook');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(field(fixture, 'agent').querySelector<HTMLSelectElement>('.select')!.value).toBe('a-1');
  });

  it('derives a path from the agent and the name when none is given', async () => {
    const { fixture, store } = render(storeStub([]));
    press(fixture, '+ add webhook');
    await fill(fixture, 'name', 'Grafana Alerts');

    press(fixture, 'create', '.form-actions');

    expect(store.addWebhook).toHaveBeenCalledWith(
      'a-1', 'Grafana Alerts', '/hooks/atlas/grafana-alerts', ['*']);
  });

  it('keeps a path the operator typed, and splits the event filters', async () => {
    const { fixture, store } = render(storeStub([]));
    press(fixture, '+ add webhook');
    await fill(fixture, 'name', 'alerts');
    await fill(fixture, 'path', '/custom/path');
    await fill(fixture, 'event filters', 'alert.firing, alert.resolved , ');

    press(fixture, 'create', '.form-actions');

    expect(store.addWebhook).toHaveBeenCalledWith(
      'a-1', 'alerts', '/custom/path', ['alert.firing', 'alert.resolved']);
  });

  it('refuses a hook with no name', async () => {
    const { fixture, store } = render(storeStub([]));
    press(fixture, '+ add webhook');
    await fixture.whenStable();
    fixture.detectChanges();

    const create = el(fixture).querySelector<HTMLButtonElement>('.form-actions .btn.primary')!;
    expect(create.disabled).toBe(true);
    create.click();
    expect(store.addWebhook).not.toHaveBeenCalled();
  });

  it('offers nothing to add when the container has no agents to attach one to', () => {
    const store = storeStub([]);
    store.containerAgents.set([]);
    const { fixture } = render(store);

    expect(el(fixture).querySelector<HTMLButtonElement>('.page-head .btn')!.disabled).toBe(true);
  });
});
