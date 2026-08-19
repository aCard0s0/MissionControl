import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile, HermesContainer } from '../core/models';
import { AgentsPage, agentSessionCommand } from './agents';
import { el, press, settle } from '../testing/dom';
import { agent, container as buildContainer } from '../testing/models';

describe('Agent shell shortcut command', () => {
  it('scopes a named profile with -p', () => {
    expect(agentSessionCommand('ops-bot')).toBe('hermes -p ops-bot');
  });

  it('invokes the default profile bare — hermes takes -p only for named ones', () => {
    expect(agentSessionCommand('default')).toBe('hermes');
  });

  it('accepts the punctuation hermes allows in a profile directory name', () => {
    expect(agentSessionCommand('ops.bot_2-v1')).toBe('hermes -p ops.bot_2-v1');
  });

  it('refuses a name carrying shell metacharacters so nothing is typed blind', () => {
    for (const name of ['ops; rm -rf /', 'ops bot', 'ops$(id)', 'ops`id`', 'ops&&id', '../escape', '']) {
      expect(agentSessionCommand(name)).toBeUndefined();
    }
  });
});

const container: HermesContainer = buildContainer('c-1', { name: 'hermes-prod' });

/** Only what the roster and its create dialog reach for on the store. */
const storeStub = (agents: AgentProfile[]) => ({
  containerAgents: signal(agents),
  containers: signal([container]),
  selectedContainer: signal(container),
  agentById: (id: string) => agents.find(a => a.id === id) ?? null,
  openTerminal: vi.fn(),
  // the create dialog, rendered as a child
  profileTemplates: signal([]),
  llmProviders: signal([]),
  modelProviders: signal([]),
  templateById: () => null,
  authProviders: vi.fn().mockResolvedValue([]),
  modelCatalog: vi.fn().mockResolvedValue([]),
  modelCatalogLive: vi.fn().mockResolvedValue([]),
  providerModels: vi.fn().mockResolvedValue([]),
  createAgent: vi.fn().mockResolvedValue('a-new'),
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: HermesStore, useValue: store }],
  });
  const fixture = TestBed.createComponent(AgentsPage);
  fixture.detectChanges();
  return { fixture, store };
};

describe('AgentsPage roster', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('shows one card per profile, with the day\'s totals across them', () => {
    const { fixture } = render(storeStub([
      agent('atlas', { state: 'active', msgsToday: 12, tokensToday: 30 }),
      agent('scribe', { msgsToday: 8, tokensToday: 5 }),
    ]));

    expect(el(fixture).querySelectorAll('.card-wrap').length).toBe(2);
    expect(el(fixture).textContent).toContain('atlas');
    const head = el(fixture).querySelector('.head-stats')!.textContent ?? '';
    expect(head).toContain('active');
    expect(head).toContain('msgs today');
  });

  it('says so when the container holds no profiles', () => {
    const { fixture } = render(storeStub([]));

    expect(el(fixture).textContent).toContain('No profiles in this container');
    expect(el(fixture).querySelectorAll('.card-wrap').length).toBe(0);
  });

  it('opens a shell on the agent\'s own session', () => {
    const { fixture, store } = render(storeStub([agent('atlas')]));

    el(fixture).querySelector<HTMLButtonElement>('.shell-btn')!.click();

    expect(store.openTerminal).toHaveBeenCalledWith({
      hostId: 'dh-local', containerId: 'c-1', label: 'atlas',
      agentKey: 'atlas', command: 'hermes -p atlas',
    });
  });

  it('lists only the integrations that are up or degraded', () => {
    const { fixture } = render(storeStub([agent('atlas', {
      integrations: [
        { kind: 'slack', status: 'up', detail: '' },
        { kind: 'email', status: 'degraded', detail: '' },
        { kind: 'discord', status: 'off', detail: '' },
      ],
    })]));

    const chips = Array.from(el(fixture).querySelectorAll('.chips .chip'))
      .map(c => (c.textContent ?? '').trim());
    expect(chips).toEqual(['slack', 'email']);
  });

  it('opens the create dialog on the selected container', async () => {
    const { fixture } = render(storeStub([]));

    press(fixture, '+ new agent');
    await settle(fixture);

    expect(el(fixture).querySelector('mc-agent-create-dialog')).not.toBeNull();
    expect(el(fixture).textContent).toContain('NEW AGENT PROFILE — hermes-prod');
  });

  it('takes the dialog away again on cancel', async () => {
    const { fixture } = render(storeStub([]));
    press(fixture, '+ new agent');
    await settle(fixture);

    el(fixture).querySelector<HTMLButtonElement>('.modal-actions .btn.ghost')!.click();
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-agent-create-dialog')).toBeNull();
  });

  it('offers nothing to create when no container is selected', () => {
    const store = storeStub([]);
    store.selectedContainer.set(null as never);
    const { fixture } = render(store);

    expect(el(fixture).querySelector<HTMLButtonElement>('.page-head .btn')!.disabled).toBe(true);
  });
});
