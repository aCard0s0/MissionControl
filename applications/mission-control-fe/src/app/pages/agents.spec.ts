import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile, HermesContainer } from '../core/models';
import { AgentsPage, agentSessionCommand } from './agents';

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

const agent = (id: string, patch: Partial<AgentProfile> = {}): AgentProfile => ({
  id, containerId: 'c-1', name: id, role: 'ops', state: 'idle', provider: 'nous', model: 'm',
  apiKeyMasked: '…', cwd: '/opt/data', soul: '', memoryMd: '', configYaml: '', skills: [],
  mcp: [], integrations: [], sessions: [], msgsToday: 0, tokensToday: 0, errorRate: 0,
  lastActive: 1, ...patch,
});

const container: HermesContainer = {
  id: 'c-1', name: 'hermes-prod', shortId: 'c1', hostId: 'dh-local', status: 'running',
  image: 'hermes', version: 'v1', startedAt: 1, cpu: 0, ram: 0, ramTotal: 0, disk: 0,
  diskTotal: 0, netIn: 0, netOut: 0, cpuHist: [], ramHist: [], netHist: [],
};

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
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: HermesStore, useValue: store }],
  });
  const fixture = TestBed.createComponent(AgentsPage);
  fixture.detectChanges();
  return { fixture, store };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void };

const settle = async (fixture: Fixture): Promise<void> => {
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
};

const press = (fixture: Fixture, label: string): void => {
  const match = Array.from(el(fixture).querySelectorAll('button'))
    .find(b => (b.textContent ?? '').trim() === label);
  if (!match) throw new Error(`no button labelled "${label}"`);
  (match as HTMLButtonElement).click();
  fixture.detectChanges();
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
