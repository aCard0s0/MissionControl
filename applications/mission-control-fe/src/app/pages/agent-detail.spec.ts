import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile, ChatMessage, SessionInfo, SkillRef } from '../core/models';
import { AgentDetailPage } from './agent-detail';

const skill = (name: string, enabled = true): SkillRef =>
  ({ id: `s-${name}`, name, source: 'bundled', version: '1.0.0', description: name, enabled });

const session: SessionInfo = {
  id: 'sess-1', title: 'Monday digest', platform: 'cli', startedAt: 1, messages: 2, status: 'closed',
};

const messages: ChatMessage[] = [
  { role: 'user', content: 'status?', ts: 1 } as ChatMessage,
  { role: 'assistant', content: 'all green', ts: 2 } as ChatMessage,
];

const agent = (patch: Partial<AgentProfile> = {}): AgentProfile => ({
  id: 'a-atlas', containerId: 'c-1', name: 'atlas', role: 'Ops & infrastructure', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…9f2c', cwd: '/opt/data',
  soul: '# SOUL.md — atlas\n', memoryMd: '# MEMORY.md\n', configYaml: 'provider: anthropic\n',
  skills: [skill('ops'), skill('research', false)],
  mcp: [], integrations: [], sessions: [], msgsToday: 4, tokensToday: 9, errorRate: 0,
  lastActive: 1, ...patch,
});

/** Only what the page and its tab panels reach for on the store. */
const storeStub = (profile: AgentProfile | null) => ({
  agentById: (id: string | null) => (profile && id === profile.id ? profile : null),
  containerJobs: signal([]),
  agentSessions: vi.fn().mockResolvedValue([session]),
  agentSessionMessages: vi.fn().mockResolvedValue(messages),
  deleteAgentSession: vi.fn().mockResolvedValue(undefined),
  agentLogTail: vi.fn().mockResolvedValue([]),
  updateSoul: vi.fn().mockResolvedValue(true),
  updateAgentConfig: vi.fn().mockResolvedValue(true),
  pingIntegrations: vi.fn(),
  removeAgent: vi.fn(),
  captureTemplate: vi.fn().mockResolvedValue('pt-new'),
  toast: vi.fn(),
  // the tab panels, rendered as children
  agentSetupOf: () => null,
  agentSetupLoading: () => false,
  agentSetup: vi.fn().mockResolvedValue(null),
  setAgentEnv: vi.fn().mockResolvedValue(null),
  initAgentEnv: vi.fn().mockResolvedValue(null),
  toggleSkill: vi.fn(),
  addSkill: vi.fn(),
  removeSkill: vi.fn(),
  getSkillContent: vi.fn().mockResolvedValue(null),
  saveSkillContent: vi.fn().mockResolvedValue(true),
  mcpServers: signal([]),
  mcpServerById: () => null,
  dockerHosts: signal([]),
  hostById: () => null,
  addMcp: vi.fn().mockResolvedValue(true),
  updateMcp: vi.fn().mockResolvedValue(true),
  setMcpEnabled: vi.fn().mockResolvedValue(true),
  connectCatalogMcp: vi.fn().mockResolvedValue(true),
  syncCatalogMcp: vi.fn().mockResolvedValue(true),
  unlinkCatalogMcp: vi.fn().mockResolvedValue(true),
  removeMcp: vi.fn().mockResolvedValue(true),
  testMcp: vi.fn().mockResolvedValue(true),
});

const render = (store: ReturnType<typeof storeStub>, agentId = 'a-atlas') => {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: HermesStore, useValue: store },
      { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: agentId })) } },
    ],
  });
  const fixture = TestBed.createComponent(AgentDetailPage);
  fixture.detectChanges();
  return { fixture, store };
};

const el = (fixture: { nativeElement: unknown }): HTMLElement => fixture.nativeElement as HTMLElement;

type Fixture = { nativeElement: unknown; detectChanges(): void };

const settle = async (fixture: Fixture): Promise<void> => {
  await vi.advanceTimersByTimeAsync(0);
  fixture.detectChanges();
};

const openTab = (fixture: Fixture, name: string): void => {
  const tab = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.tabbar .tab'))
    .find(b => (b.textContent ?? '').trim() === name);
  if (!tab) throw new Error(`no tab named "${name}"`);
  tab.click();
  fixture.detectChanges();
};

describe('AgentDetailPage', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('opens on the overview of the profile named in the route', () => {
    const { fixture } = render(storeStub(agent()));

    expect(el(fixture).textContent).toContain('atlas');
    expect(el(fixture).textContent).toContain('Ops & infrastructure');
    expect(el(fixture).querySelector('.tabbar')).not.toBeNull();
  });

  it('says so when the route names a profile the active container does not have', () => {
    const { fixture } = render(storeStub(null), 'a-nonexistent');

    expect(el(fixture).textContent).toContain('Agent not found');
  });

  it('counts only the skills the profile actually runs with', () => {
    const { fixture } = render(storeStub(agent()));

    expect(el(fixture).textContent).toContain('1');
  });

  it('hands the skills tab to its own panel', () => {
    const { fixture } = render(storeStub(agent()));
    openTab(fixture, 'skills');

    expect(el(fixture).querySelector('mc-agent-skills-panel')).not.toBeNull();
    expect(el(fixture).querySelectorAll('.skill-item').length).toBe(2);
  });

  it('hands the mcp tab to its own panel', () => {
    const { fixture } = render(storeStub(agent()));
    openTab(fixture, 'mcp');

    expect(el(fixture).querySelector('mc-agent-mcp-panel')).not.toBeNull();
    expect(el(fixture).textContent).toContain('CONNECT FROM MCP CATALOG');
  });

  it('lists the profile\'s recorded sessions and opens one in the viewer', async () => {
    const { fixture, store } = render(storeStub(agent()));

    openTab(fixture, 'sessions');
    await settle(fixture);
    expect(store.agentSessions).toHaveBeenCalledWith('a-atlas');
    expect(el(fixture).querySelectorAll('.sess-row').length).toBe(1);

    const view = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.sess-row button'))
      .find(b => (b.textContent ?? '').trim() === 'view')!;
    view.click();
    await settle(fixture);

    expect(el(fixture).querySelector('mc-session-viewer')).not.toBeNull();
    expect(el(fixture).querySelectorAll('.trow').length).toBe(2);
  });

  it('shows the profile files read-only, and switches which one is shown', () => {
    const { fixture } = render(storeStub(agent()));
    openTab(fixture, 'files');

    expect(el(fixture).textContent).toContain('SOUL.md');
    const memory = Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.file-tab'))
      .find(b => (b.textContent ?? '').trim() === 'MEMORY.md')!;
    memory.click();
    fixture.detectChanges();

    const shown = el(fixture).querySelector('pre.file')?.textContent ?? '';
    expect(shown).toContain('# MEMORY.md');
    expect(el(fixture).querySelector('pre.file')?.getAttribute('contenteditable')).toBeNull();
  });

  it('polls the gateway log only while Activity is open', async () => {
    const { fixture, store } = render(storeStub(agent()));
    expect(store.agentLogTail).not.toHaveBeenCalled();

    openTab(fixture, 'activity');
    await settle(fixture);
    expect(store.agentLogTail).toHaveBeenCalledWith('a-atlas', 100);

    const afterOpen = store.agentLogTail.mock.calls.length;
    openTab(fixture, 'overview');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(store.agentLogTail.mock.calls.length).toBe(afterOpen);
  });
});
