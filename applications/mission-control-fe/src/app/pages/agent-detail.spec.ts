import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentMcpStore } from '../core/store/agent-mcp-store';
import { AgentRemoval } from '../core/store/agent-removal';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { AgentSkillStore } from '../core/store/agent-skill-store';
import { AgentStore } from '../core/store/agent-store';
import { HostStore } from '../core/store/host-store';
import { JobStore } from '../core/store/job-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { StoreContext } from '../core/store/store-context';
import { TemplateStore } from '../core/store/template-store';
import { AgentProfile, ChatMessage, SessionInfo } from '../core/models';
import { AgentDetailPage } from './agent-detail';
import { TestFixture, button, el, fill, press, settle, text } from '../testing/dom';
import { agent as buildAgent, skill } from '../testing/models';

/** The profile every test on this page is about. */
const agent = (patch: Partial<AgentProfile> = {}): AgentProfile => buildAgent('a-atlas', {
  name: 'atlas', role: 'Ops & infrastructure', apiKeyMasked: '…9f2c',
  soul: '# SOUL.md — atlas\n', memoryMd: '# MEMORY.md\n', configYaml: 'provider: anthropic\n',
  skills: [skill('ops'), skill('research', { enabled: false })],
  msgsToday: 4, tokensToday: 9, ...patch,
});

const session: SessionInfo = {
  id: 'sess-1', title: 'Monday digest', platform: 'cli', startedAt: 1, messages: 2, status: 'closed',
};

const messages: ChatMessage[] = [
  { role: 'user', content: 'status?', ts: 1 } as ChatMessage,
  { role: 'assistant', content: 'all green', ts: 2 } as ChatMessage,
];

/** Only what the page and its tab panels reach for on the store. The profile is
 *  held in a signal so a test can replay what the 12s poll does to it. */
const storeStub = (profile: AgentProfile | null) => {
  const held = signal(profile ? [profile] : []);
  return {
    held,
    agents: {
      byId: (id: string | null) => held().find(a => a.id === id) ?? null,
      logTail: vi.fn().mockResolvedValue([]),
      updateSoul: vi.fn().mockResolvedValue(true),
      updateConfig: vi.fn().mockResolvedValue(true),
      pingIntegrations: vi.fn(),
    },
    removal: { remove: vi.fn() },
    jobs: { forSelectedContainer: signal([]) },
    templates: { capture: vi.fn().mockResolvedValue('pt-new') },
    ctx: { toast: vi.fn() },
    // the tab panels, rendered as children
    setup: {
      setupOf: () => null,
      isSetupLoading: () => false,
      setup: vi.fn().mockResolvedValue(null),
      setEnv: vi.fn().mockResolvedValue(null),
      initEnv: vi.fn().mockResolvedValue(null),
      sessions: vi.fn().mockResolvedValue([session]),
      sessionMessages: vi.fn().mockResolvedValue(messages),
      deleteSession: vi.fn().mockResolvedValue(undefined),
    },
    skills: {
      toggle: vi.fn(),
      add: vi.fn(),
      remove: vi.fn(),
      content: vi.fn().mockResolvedValue(null),
      saveContent: vi.fn().mockResolvedValue(true),
    },
    catalog: { servers: signal([]), byId: () => null },
    hosts: { hosts: signal([]), byId: () => null },
    agentMcp: {
      add: vi.fn().mockResolvedValue(true),
      update: vi.fn().mockResolvedValue(true),
      setEnabled: vi.fn().mockResolvedValue(true),
      connectCatalog: vi.fn().mockResolvedValue(true),
      syncCatalog: vi.fn().mockResolvedValue(true),
      unlinkCatalog: vi.fn().mockResolvedValue(true),
      remove: vi.fn().mockResolvedValue(true),
      test: vi.fn().mockResolvedValue(true),
    },
  };
};

const render = (store: ReturnType<typeof storeStub>, agentId = 'a-atlas', tab?: string) => {
  // a live paramMap, so a test can navigate to another profile the way the
  // router does rather than tearing the page down
  const route = new BehaviorSubject(convertToParamMap({ id: agentId }));
  // ?tab= — how the overview links straight at one tab of a profile
  const queryParams = new BehaviorSubject(convertToParamMap(tab ? { tab } : {}));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AgentMcpStore, useValue: store.agentMcp },
      { provide: AgentRemoval, useValue: store.removal },
      { provide: AgentSetupStore, useValue: store.setup },
      { provide: AgentSkillStore, useValue: store.skills },
      { provide: AgentStore, useValue: store.agents },
      { provide: HostStore, useValue: store.hosts },
      { provide: JobStore, useValue: store.jobs },
      { provide: McpCatalogStore, useValue: store.catalog },
      { provide: StoreContext, useValue: store.ctx },
      { provide: TemplateStore, useValue: store.templates },
      { provide: ActivatedRoute, useValue: { paramMap: route, queryParamMap: queryParams } },
    ],
  });
  // the real router, with navigation recorded — RouterLink in these templates
  // needs the routes provider intact
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(AgentDetailPage);
  fixture.detectChanges();
  return { fixture, store, navigate, route, queryParams };
};

const openTab = (fixture: TestFixture, name: string): void => {
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
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('opens on the overview of the profile named in the route', () => {
    const { fixture } = render(storeStub(agent()));

    expect(el(fixture).textContent).toContain('atlas');
    expect(el(fixture).textContent).toContain('Ops & infrastructure');
    expect(el(fixture).querySelector('.tabbar')).not.toBeNull();
  });

  it('opens on the tab the link asked for', () => {
    // the overview's MCP chip links here
    const { fixture } = render(storeStub(agent()), 'a-atlas', 'mcp');

    expect(el(fixture).querySelector('mc-agent-mcp-panel')).not.toBeNull();
  });

  it('follows a later link to another tab of the same profile', () => {
    const { fixture, queryParams } = render(storeStub(agent()), 'a-atlas', 'mcp');

    // the router reuses this component when only the query string changes
    queryParams.next(convertToParamMap({ tab: 'skills' }));
    fixture.detectChanges();

    expect(el(fixture).querySelector('mc-agent-skills-panel')).not.toBeNull();
    expect(el(fixture).querySelector('mc-agent-mcp-panel')).toBeNull();
  });

  it('ignores a tab name it does not have, rather than showing nothing', () => {
    const { fixture } = render(storeStub(agent()), 'a-atlas', 'not-a-tab');

    expect(el(fixture).textContent).toContain('Ops & infrastructure');
  });

  it('leaves the URL alone when the operator presses a tab', () => {
    const { fixture, navigate } = render(storeStub(agent()));

    openTab(fixture, 'mcp');

    // flipping tabs is not navigation — it must not fill the back button either
    expect(navigate).not.toHaveBeenCalled();
    expect(el(fixture).querySelector('mc-agent-mcp-panel')).not.toBeNull();
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
    expect(store.setup.sessions).toHaveBeenCalledWith('a-atlas');
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
    expect(store.agents.logTail).not.toHaveBeenCalled();

    openTab(fixture, 'activity');
    await settle(fixture);
    expect(store.agents.logTail).toHaveBeenCalledWith('a-atlas', 100);

    const afterOpen = store.agents.logTail.mock.calls.length;
    openTab(fixture, 'overview');
    await vi.advanceTimersByTimeAsync(10_000);
    expect(store.agents.logTail.mock.calls.length).toBe(afterOpen);
  });
});

/** Types into the file editor of whichever profile file is on screen. */
const edit = async (fixture: TestFixture, value: string): Promise<void> => {
  const area = el(fixture).querySelector<HTMLTextAreaElement>('textarea.soul')!;
  area.value = value;
  area.dispatchEvent(new Event('input'));
  await settle(fixture);
};

const fileTab = (fixture: TestFixture, name: string): void => {
  Array.from(el(fixture).querySelectorAll<HTMLButtonElement>('.file-tab'))
    .find(b => (b.textContent ?? '').trim() === name)!.click();
  fixture.detectChanges();
};

describe('AgentDetailPage profile files', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('offers no save until the draft actually differs', () => {
    const { fixture } = render(storeStub(agent()));
    openTab(fixture, 'files');

    expect(button(fixture, 'save').disabled).toBe(true);
    expect(button(fixture, 'revert').disabled).toBe(true);
  });

  it('saves an edited SOUL and confirms it, briefly', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'files');
    await edit(fixture, '# SOUL.md — edited\n');

    press(fixture, 'save');
    await settle(fixture);

    expect(store.agents.updateSoul).toHaveBeenCalledWith('a-atlas', '# SOUL.md — edited\n');
    expect(text(fixture)).toContain('saved ✓');

    await settle(fixture, 2_000);
    expect(text(fixture)).not.toContain('saved ✓');
  });

  it('does not claim a save that failed', async () => {
    const store = storeStub(agent());
    store.agents.updateSoul.mockResolvedValue(false);
    const { fixture } = render(store);
    openTab(fixture, 'files');
    await edit(fixture, '# SOUL.md — edited\n');

    press(fixture, 'save');
    await settle(fixture);

    expect(text(fixture)).not.toContain('saved ✓');
  });

  it('throws an edit away on revert', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'files');
    await edit(fixture, '# SOUL.md — edited\n');

    press(fixture, 'revert');
    await settle(fixture);

    expect(button(fixture, 'save').disabled).toBe(true);
    expect(store.agents.updateSoul).not.toHaveBeenCalled();
  });

  it('saves config.yaml through its own call', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'files');
    fileTab(fixture, 'config.yaml');
    await edit(fixture, 'provider: nous\n');

    press(fixture, 'save');
    await settle(fixture);

    expect(store.agents.updateConfig).toHaveBeenCalledWith('a-atlas', 'provider: nous\n');
    expect(store.agents.updateSoul).not.toHaveBeenCalled();
  });

  it('lists only this profile\'s jobs, and says so when it has none', () => {
    const store = storeStub(agent());
    store.jobs.forSelectedContainer.set([
      { id: 'j-1', agentId: 'a-atlas', name: 'digest', schedule: '@daily', deliverTo: 'log',
        enabled: true, prompt: 'summarize', lastRun: null, lastStatus: null, nextRun: 5_000 },
      { id: 'j-2', agentId: 'a-scribe', name: 'other', schedule: '@daily', deliverTo: 'log',
        enabled: false, prompt: 'x', lastRun: null, lastStatus: null, nextRun: 5_000 },
    ] as never);
    const { fixture } = render(store);

    openTab(fixture, 'jobs');

    expect(el(fixture).querySelectorAll('.job-row')).toHaveLength(1);
    expect(text(fixture)).toContain('digest');
    expect(text(fixture)).not.toContain('No jobs assigned');
  });

  it('says the profile has no schedule of its own', () => {
    const { fixture } = render(storeStub(agent()));

    openTab(fixture, 'jobs');

    expect(text(fixture)).toContain('No jobs assigned to this agent.');
  });

  it('shows MEMORY.md read-only, with no save at all', () => {
    const { fixture } = render(storeStub(agent()));
    openTab(fixture, 'files');
    fileTab(fixture, 'MEMORY.md');

    expect(el(fixture).querySelector('textarea.soul')).toBeNull();
    expect(el(fixture).querySelector('pre.file')?.textContent).toContain('# MEMORY.md');
  });
});

describe('AgentDetailPage sessions', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('says so when the profile has no recorded sessions', async () => {
    const store = storeStub(agent());
    store.setup.sessions.mockResolvedValue([]);
    const { fixture } = render(store);

    openTab(fixture, 'sessions');
    await settle(fixture);

    expect(text(fixture)).toContain('No sessions recorded for this agent.');
  });

  it('treats a failed listing as no sessions rather than as a broken tab', async () => {
    const store = storeStub(agent());
    store.setup.sessions.mockRejectedValue(new Error('no session dir'));
    const { fixture } = render(store);

    openTab(fixture, 'sessions');
    await settle(fixture);

    expect(text(fixture)).toContain('No sessions recorded for this agent.');
  });

  it('reads the listing once per visit to the tab, and again on demand', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'sessions');
    await settle(fixture);

    openTab(fixture, 'overview');
    openTab(fixture, 'sessions');
    await settle(fixture);
    expect(store.setup.sessions).toHaveBeenCalledTimes(1);

    press(fixture, 'refresh');
    await settle(fixture);
    expect(store.setup.sessions).toHaveBeenCalledTimes(2);
  });

  it('drops a deleted session from the list, and closes it if it was open', async () => {
    vi.stubGlobal('confirm', () => true);
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'sessions');
    await settle(fixture);
    press(fixture, 'view', '.sess-row');
    await settle(fixture);

    press(fixture, 'delete', '.sess-row');
    await settle(fixture);

    expect(store.setup.deleteSession).toHaveBeenCalledWith('a-atlas', 'sess-1');
    expect(el(fixture).querySelectorAll('.sess-row').length).toBe(0);
    expect(el(fixture).querySelector('mc-session-viewer')).toBeNull();
    vi.unstubAllGlobals();
  });

  it('keeps the session when the operator backs out of the confirmation', async () => {
    vi.stubGlobal('confirm', () => false);
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'sessions');
    await settle(fixture);

    press(fixture, 'delete', '.sess-row');
    await settle(fixture);

    expect(store.setup.deleteSession).not.toHaveBeenCalled();
    expect(el(fixture).querySelectorAll('.sess-row').length).toBe(1);
    vi.unstubAllGlobals();
  });

  it('says why a session could not be deleted, and keeps it', async () => {
    vi.stubGlobal('confirm', () => true);
    const store = storeStub(agent());
    store.setup.deleteSession.mockRejectedValue(new Error('file locked'));
    const { fixture } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);

    press(fixture, 'delete', '.sess-row');
    await settle(fixture);

    expect(store.ctx.toast).toHaveBeenCalledWith('session delete failed: file locked');
    expect(el(fixture).querySelectorAll('.sess-row').length).toBe(1);
    vi.unstubAllGlobals();
  });

  it('closes the viewer and says why when a transcript cannot be read', async () => {
    const store = storeStub(agent());
    store.setup.sessionMessages.mockRejectedValue(new Error('corrupt transcript'));
    const { fixture } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);

    press(fixture, 'view', '.sess-row');
    await settle(fixture);

    expect(store.ctx.toast).toHaveBeenCalledWith('session load failed: corrupt transcript');
    expect(el(fixture).querySelector('mc-session-viewer')).toBeNull();
  });

  it('downloads a transcript as a file named after the profile and session', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'sessions');
    await settle(fixture);

    // the anchor a download goes out through, and the blob URL it points at
    const anchor = document.createElement('a');
    const click = vi.spyOn(anchor, 'click').mockImplementation(() => { /* no navigation */ });
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(
      ((tag: string, options?: ElementCreationOptions) =>
        tag === 'a' ? anchor : create(tag, options)) as never);
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', Object.assign(Object.create(URL), {
      createObjectURL: () => 'blob:transcript', revokeObjectURL,
    }));

    press(fixture, 'download', '.sess-row');
    await settle(fixture);

    expect(store.setup.sessionMessages).toHaveBeenCalledWith('a-atlas', 'sess-1');
    expect(anchor.download).toBe('atlas-sess-1.json');
    expect(click).toHaveBeenCalled();
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:transcript');
  });

  it('says so rather than downloading nothing when the transcript cannot be read', async () => {
    const store = storeStub(agent());
    store.setup.sessionMessages.mockRejectedValue(new Error('gone'));
    const { fixture } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);

    press(fixture, 'download', '.sess-row');
    await settle(fixture);

    expect(store.ctx.toast).toHaveBeenCalledWith('session download failed');
  });
});

describe('AgentDetailPage profile actions', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('re-probes the integrations, and shows that it is doing so', async () => {
    const { fixture, store } = render(storeStub(agent()));

    press(fixture, 'check connectivity');

    expect(store.agents.pingIntegrations).toHaveBeenCalledWith('a-atlas');
    expect(text(fixture)).toContain('checking…');

    await settle(fixture, 1_200);
    expect(text(fixture)).toContain('check connectivity');
  });

  it('holds the delete until the profile name is typed exactly', async () => {
    const { fixture, store, navigate } = render(storeStub(agent()));

    press(fixture, 'delete profile');
    expect(button(fixture, 'delete permanently').disabled).toBe(true);

    await fill(fixture, 'type', 'atla');
    expect(button(fixture, 'delete permanently').disabled).toBe(true);

    await fill(fixture, 'type', 'atlas');
    press(fixture, 'delete permanently');

    expect(store.removal.remove).toHaveBeenCalledWith('a-atlas');
    expect(navigate).toHaveBeenCalledWith(['/agents']);
  });

  it('cancels the delete without touching the profile', () => {
    const { fixture, store } = render(storeStub(agent()));
    press(fixture, 'delete profile');

    press(fixture, 'cancel');

    expect(el(fixture).querySelector('.modal')).toBeNull();
    expect(store.removal.remove).not.toHaveBeenCalled();
  });

  it('proposes a template name derived from the profile', async () => {
    const { fixture } = render(storeStub(agent()));

    press(fixture, 'save as template');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('.modal .input')!.value)
      .toBe('atlas-template');
  });

  it('captures the profile, says so, and leaves for the template list', async () => {
    const { fixture, store, navigate } = render(storeStub(agent()));
    press(fixture, 'save as template');
    await fill(fixture, 'template name', 'ops-blueprint');

    press(fixture, 'save template');
    await settle(fixture);

    expect(store.templates.capture).toHaveBeenCalledWith('a-atlas', 'ops-blueprint');
    expect(store.ctx.toast).toHaveBeenCalledWith('saved template "ops-blueprint"');
    expect(navigate).toHaveBeenCalledWith(['/profiles']);
  });

  it('falls back to the derived name when the field is cleared', async () => {
    const { fixture, store } = render(storeStub(agent()));
    press(fixture, 'save as template');
    await fill(fixture, 'template name', '   ');

    press(fixture, 'save template');
    await settle(fixture);

    expect(store.templates.capture).toHaveBeenCalledWith('a-atlas', 'atlas-template');
  });

  it('keeps the modal open when the capture failed', async () => {
    const store = storeStub(agent());
    store.templates.capture.mockResolvedValue('');
    const { fixture, navigate } = render(store);
    press(fixture, 'save as template');

    press(fixture, 'save template');
    await settle(fixture);

    expect(el(fixture).querySelector('.modal')).not.toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });
});

describe('AgentDetailPage activity', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('keeps re-reading the gateway log while the tab is open', async () => {
    const { fixture, store } = render(storeStub(agent()));
    openTab(fixture, 'activity');
    await settle(fixture);

    await settle(fixture, 10_000);

    expect(store.agents.logTail.mock.calls.length).toBeGreaterThan(1);
  });

  it('shows why the log could not be read', async () => {
    const store = storeStub(agent());
    store.agents.logTail.mockRejectedValue(new Error('gateway down'));
    const { fixture } = render(store);

    openTab(fixture, 'activity');
    await settle(fixture);

    expect(text(fixture)).toContain('gateway down');
  });
});

/** Routes the open page at another profile, the way the router would. */
const navigateTo = (
  store: ReturnType<typeof storeStub>,
  route: { next(map: ReturnType<typeof convertToParamMap>): void },
  profile: AgentProfile,
): void => {
  store.held.update(all => [...all, profile]);
  route.next(convertToParamMap({ id: profile.id }));
};

describe('AgentDetailPage against the poll', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('picks up a SOUL change from the poll while the draft is untouched', async () => {
    const store = storeStub(agent());
    const { fixture } = render(store);
    openTab(fixture, 'files');

    store.held.set([agent({ soul: '# SOUL.md — rewritten elsewhere\n' })]);
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLTextAreaElement>('textarea.soul')!.value)
      .toContain('rewritten elsewhere');
  });

  it('never overwrites an edit in progress with what the poll brought back', async () => {
    const store = storeStub(agent());
    const { fixture } = render(store);
    openTab(fixture, 'files');
    await settle(fixture);            // let the draft-sync effect take its first run
    await edit(fixture, '# SOUL.md — my unsaved edit\n');

    store.held.set([agent({ soul: '# SOUL.md — rewritten elsewhere\n' })]);
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLTextAreaElement>('textarea.soul')!.value)
      .toContain('my unsaved edit');
  });

  it('resets the drafts outright when a different profile loads', async () => {
    const store = storeStub(agent());
    const { fixture, route } = render(store);
    openTab(fixture, 'files');
    await edit(fixture, '# SOUL.md — my unsaved edit\n');

    navigateTo(store, route, agent({ id: 'a-scribe', name: 'scribe', soul: '# SOUL.md — scribe\n' }));
    await settle(fixture);

    // a different profile is a different file; the edit does not follow it
    expect(el(fixture).querySelector<HTMLTextAreaElement>('textarea.soul')?.value)
      .toContain('scribe');
  });

  it('re-reads the sessions of a profile that replaced the one being viewed', async () => {
    const store = storeStub(agent());
    const { fixture, route } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);

    navigateTo(store, route, agent({ id: 'a-scribe', name: 'scribe' }));
    await settle(fixture);

    expect(store.setup.sessions).toHaveBeenCalledWith('a-scribe');
  });

  it('drops a session listing that landed after the profile changed', async () => {
    let land!: (value: unknown[]) => void;
    const store = storeStub(agent());
    store.setup.sessions.mockReturnValueOnce(new Promise(resolve => { land = resolve; }))
      .mockResolvedValue([]);
    const { fixture, route } = render(store);
    openTab(fixture, 'sessions');

    navigateTo(store, route, agent({ id: 'a-scribe', name: 'scribe' }));
    await settle(fixture);
    land([session]);
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.sess-row')).toHaveLength(0);
  });

  it('drops a transcript that landed after the modal was closed', async () => {
    let land!: (value: unknown[]) => void;
    const store = storeStub(agent());
    store.setup.sessionMessages.mockReturnValue(new Promise(resolve => { land = resolve; }));
    const { fixture } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);
    press(fixture, 'view', '.sess-row');
    await settle(fixture);

    press(fixture, 'close', 'mc-session-viewer');
    land(messages);
    await settle(fixture);

    expect(el(fixture).querySelector('mc-session-viewer')).toBeNull();
  });

  it('downloads the transcript already on screen rather than reading it again', async () => {
    const store = storeStub(agent());
    const { fixture } = render(store);
    openTab(fixture, 'sessions');
    await settle(fixture);
    press(fixture, 'view', '.sess-row');
    await settle(fixture);
    const afterView = store.setup.sessionMessages.mock.calls.length;

    const anchor = document.createElement('a');
    vi.spyOn(anchor, 'click').mockImplementation(() => { /* no navigation */ });
    const create = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation(
      ((tag: string, options?: ElementCreationOptions) =>
        tag === 'a' ? anchor : create(tag, options)) as never);
    vi.stubGlobal('URL', Object.assign(Object.create(URL), {
      createObjectURL: () => 'blob:transcript', revokeObjectURL: () => { /* nothing to free */ },
    }));

    press(fixture, 'download', 'mc-session-viewer');
    await settle(fixture);

    expect(store.setup.sessionMessages.mock.calls.length).toBe(afterView);
    expect(anchor.download).toBe('atlas-sess-1.json');
  });

  it('does not confirm a save that landed after the profile changed', async () => {
    let land!: (value: boolean) => void;
    const store = storeStub(agent());
    store.agents.updateSoul.mockReturnValue(new Promise(resolve => { land = resolve; }));
    const { fixture, route } = render(store);
    openTab(fixture, 'files');
    await edit(fixture, '# SOUL.md — edited\n');
    press(fixture, 'save');

    navigateTo(store, route, agent({ id: 'a-scribe', name: 'scribe' }));
    await settle(fixture);
    land(true);
    await settle(fixture);

    expect(text(fixture)).not.toContain('saved ✓');
  });

  it('keeps polling the gateway log only for the profile still on screen', async () => {
    const store = storeStub(agent());
    const { fixture, route } = render(store);
    openTab(fixture, 'activity');
    await settle(fixture);

    navigateTo(store, route, agent({ id: 'a-scribe', name: 'scribe' }));
    await settle(fixture, 6_000);

    const asked = store.agents.logTail.mock.calls.map(c => c[0]);
    expect(asked.at(-1)).toBe('a-scribe');
  });
});
