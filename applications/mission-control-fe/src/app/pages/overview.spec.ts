import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { JobStore } from '../core/store/job-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { LogStore } from '../core/store/log-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import {
  AgentProfile, CronJob, HermesContainer, ImageCatalog, Integration, LogEntry, McpServer, SkillRef,
} from '../core/models';
import { OverviewPage } from './overview';
import { el, text } from '../testing/dom';
import { agent, cronJob as job, mcpServer, skill as buildSkill } from '../testing/models';

const container: HermesContainer = {
  id: 'c-1', name: 'hermes-prod', shortId: 'c1', hostId: 'dh-local', status: 'running',
  image: 'hermes', version: 'v1', imageDigest: null, startedAt: 1, cpu: 12, ram: 512, ramTotal: 2048,
  disk: 4, diskTotal: 40, netIn: 1, netOut: 2, cpuHist: [1, 2], ramHist: [1, 2], netHist: [1, 2],
};

const log = (level: LogEntry['level'], msg: string): LogEntry =>
  ({ ts: 1_700_000_000_000, level, source: 'gateway', agentId: null, msg });

const storeStub = (opts: {
  agents?: AgentProfile[]; logs?: LogEntry[]; jobs?: CronJob[];
  catalog?: Record<string, ImageCatalog>;
  container?: HermesContainer;
} = {}) => ({
  containers: { selected: signal(opts.container ?? container) },
  agents: { forSelectedContainer: signal(opts.agents ?? []) },
  jobs: { forSelectedContainer: signal(opts.jobs ?? []) },
  logs: {
    selectedLogs: signal(opts.logs ?? []),
    loading: signal(false),
    error: signal<string | null>(null),
    updatedAt: signal<number | null>(1),
    refresh: vi.fn(),
  },
  terminal: { open: vi.fn(), openAgentShell: vi.fn() },
  images: { catalog: signal(opts.catalog ?? {}), refreshAll: vi.fn() },
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: AgentStore, useValue: store.agents },
      { provide: ContainerStore, useValue: store.containers },
      { provide: JobStore, useValue: store.jobs },
      { provide: LogStore, useValue: store.logs },
      { provide: ImageCatalogStore, useValue: store.images },
      { provide: TerminalRequestStore, useValue: store.terminal },
    ],
  });
  // the real router, with navigation recorded — the RouterLinks in this template
  // need the routes provider intact
  const router = TestBed.inject(Router);
  const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(OverviewPage);
  fixture.detectChanges();
  return { fixture, store, navigate };
};

/** The panel whose header names this section — several panels carry a count row. */
const panel = (fixture: { nativeElement: unknown }, heading: string): HTMLElement => {
  const match = Array.from(el(fixture).querySelectorAll<HTMLElement>('.panel'))
    .find(p => (p.querySelector('.panel-h')?.textContent ?? '').includes(heading));
  if (!match) throw new Error(`no panel headed "${heading}"`);
  return match;
};

// `data-reveal` animates through gsap on a real timer, which reads
// getComputedStyle after jsdom has torn the document down. Freezing the clock for
// the whole file keeps those tweens from outliving the test that started them.
beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
});

/** A skill from a given source — what the overview counts by. */
const skill = (source: SkillRef['source']): SkillRef => buildSkill(source, { source });

const mcp = (name: string, status: McpServer['status'], tools = 0): McpServer =>
  mcpServer(name, { status, tools, latencyMs: null });

describe('OverviewPage tallies', () => {
  it('counts the profiles by the state they are in', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { state: 'active' }), agent('a-2', { state: 'active' }),
      agent('a-3', { state: 'idle' }), agent('a-4', { state: 'dormant' }),
    ] }));

    expect(text(fixture)).toContain('2 active');
    expect(text(fixture)).toContain('1 idle');
    expect(text(fixture)).toContain('1 dormant');
  });

  it('adds up skills across profiles, and counts tools only where a server answered', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { skills: [skill('bundled'), skill('user')], mcp: [mcp('a', 'connected', 7)] }),
      agent('a-2', { skills: [skill('hub')], mcp: [mcp('b', 'error', 99), mcp('c', 'connected', 3)] }),
    ] }));

    expect(text(fixture)).toContain('3 skills');
    // a failing server's advertised tool count is not a capability the fleet has
    expect(text(fixture)).toContain('10 tools');
  });

  it('summarizes MCP health across the container', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { mcp: [mcp('a', 'connected'), mcp('b', 'error'), mcp('c', 'checking')] }),
    ] }));

    expect(text(fixture)).toContain('1 MCP connected');
    expect(text(fixture)).toContain('1 MCP failing');
    expect(text(fixture)).toContain('1 MCP unchecked');
  });

  it('says nothing about failures or unchecked servers when there are none', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { mcp: [mcp('a', 'connected')] }),
    ] }));

    expect(text(fixture)).toContain('1 MCP connected');
    expect(text(fixture)).not.toContain('MCP failing');
    expect(text(fixture)).not.toContain('MCP unchecked');
  });

  it('lets the worst status win when profiles disagree about a channel', () => {
    const integration = (kind: Integration['kind'], status: Integration['status']): Integration =>
      ({ kind, status, detail: '' });
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { integrations: [integration('slack', 'up'), integration('email', 'up')] }),
      agent('a-2', { integrations: [integration('slack', 'degraded')] }),
    ] }));

    const chips = Array.from(el(fixture).querySelectorAll('.chips .chip'));
    const labels = chips.map(c => (c.textContent ?? '').replace(/\s+/g, ' ').trim());
    // one chip per channel, sorted by name, and slack carries the degraded state
    expect(labels).toEqual(['email · up', 'slack · degraded']);
    expect(chips[1].classList.contains('warn')).toBe(true);
    expect(chips[1].classList.contains('on')).toBe(false);
  });
});

describe('OverviewPage profile rows', () => {
  it('is itself the way to the roster, with no link of its own to carry', () => {
    const { fixture, navigate } = render(storeStub({ agents: [agent('a-1')] }));
    const card = panel(fixture, 'PROFILES / AGENTS');

    expect(text(fixture)).not.toContain('all →');
    card.click();

    expect(navigate).toHaveBeenCalledWith(['/agents']);
  });

  it('sends a row to the profile, and its chips to the tab each one names', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { skills: [skill('bundled')], mcp: [mcp('a', 'connected', 4)] }),
    ] }));
    const row = panel(fixture, 'PROFILES / AGENTS').querySelector('.agent-row')!;
    const href = (sel: string) => row.querySelector(sel)!.getAttribute('href');

    expect(href('.agent-pick')).toBe('/agents/a-1');
    expect(href('.caps .cap:nth-of-type(1)')).toBe('/agents/a-1?tab=skills');
    expect(href('.caps .cap:nth-of-type(2)')).toBe('/agents/a-1?tab=mcp');
  });

  it('states each profile\'s skills and MCP reach in one chip apiece', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', {
        skills: [skill('bundled'), skill('user'), skill('user')],
        mcp: [mcp('a', 'connected', 4), mcp('b', 'error', 9)],
      }),
    ] }));
    const caps = Array.from(panel(fixture, 'PROFILES / AGENTS')
      .querySelectorAll('.caps .cap'))
      .map(c => (c.textContent ?? '').replace(/\s+/g, ' ').trim());

    // a failing server's advertised tools are not reach this profile has
    expect(caps).toEqual(['3 skills · 2 custom', '2 mcp · 4 tools']);
  });

  it('says nothing about custom skills for a profile that authored none', () => {
    const { fixture } = render(storeStub({ agents: [
      agent('a-1', { skills: [skill('bundled')] }),
    ] }));

    expect(panel(fixture, 'PROFILES / AGENTS').querySelector('.caps .cap')!.textContent)
      .not.toContain('custom');
  });

  it('opens a shell in the profile\'s session without leaving the page', () => {
    const a = agent('a-1');
    const { fixture, store, navigate } = render(storeStub({ agents: [a] }));
    const term = panel(fixture, 'PROFILES / AGENTS').querySelector<HTMLButtonElement>('.term')!;

    expect(term.getAttribute('aria-label')).toContain('a-1');
    term.click();

    expect(store.terminal.openAgentShell).toHaveBeenCalledWith(a, container);
    // the shell button sits inside a card that navigates — it must not do both
    expect(navigate).not.toHaveBeenCalled();
  });
});

describe('OverviewPage scheduled jobs', () => {
  it('is itself the way to the calendar, with no link of its own to carry', () => {
    const { fixture, navigate } = render(storeStub({ jobs: [job('j-1')] }));

    expect(text(fixture)).not.toContain('calendar →');
    panel(fixture, 'SCHEDULER').click();

    expect(navigate).toHaveBeenCalledWith(['/calendar']);
  });

  it('counts enabled against paused, and names the next one due', () => {
    const { fixture } = render(storeStub({ jobs: [
      job('j-1', { enabled: true, nextRun: 5_000 }),
      job('j-2', { enabled: true, nextRun: 3_000, name: 'job j-2' }),
      job('j-3', { enabled: false }),
    ] }));

    expect(text(fixture)).toContain('2 enabled');
    expect(text(fixture)).toContain('1 paused');
    expect(el(fixture).querySelector('.next-job')!.textContent).toContain('job j-2');
  });

  it('never offers a paused job as the next run, however soon it is due', () => {
    const { fixture } = render(storeStub({ jobs: [
      job('j-1', { enabled: false, nextRun: 1_000 }),
      job('j-2', { enabled: true, nextRun: 9_000 }),
    ] }));

    const next = el(fixture).querySelector('.next-job')!.textContent ?? '';
    expect(next).toContain('job j-2');
    expect(next).not.toContain('job j-1');
  });

  it('flags a failing job in the counts', () => {
    const { fixture } = render(storeStub({ jobs: [job('j-1', { lastStatus: 'fail' })] }));

    expect(panel(fixture, 'SCHEDULER').querySelector('.count-row')!.textContent).toContain('1 failing');
  });

  it('says nothing about failures when every job last ran clean', () => {
    const { fixture } = render(storeStub({ jobs: [job('j-1')] }));

    expect(panel(fixture, 'SCHEDULER').querySelector('.count-row')!.textContent).not.toContain('failing');
  });
});

describe('OverviewPage log tail', () => {
  it('shows the tail, and counts the errors in it', () => {
    const { fixture } = render(storeStub({ logs: [
      log('info', 'started'), log('error', 'boom'), log('error', 'again'),
    ] }));

    expect(el(fixture).querySelectorAll('.log-line').length).toBe(3);
    expect(text(fixture)).toContain('2 errors in tail');
  });

  it('says one error in the singular', () => {
    const { fixture } = render(storeStub({ logs: [log('error', 'boom')] }));

    expect(text(fixture)).toContain('1 error in tail');
  });

  it('narrows the tail by level, and says when a filter matches nothing', () => {
    const { fixture } = render(storeStub({ logs: [log('info', 'started'), log('warn', 'slow')] }));

    const filter = (label: string) => {
      const match = Array.from(el(fixture).querySelectorAll('button'))
        .find(b => (b.textContent ?? '').trim().startsWith(label))!;
      (match as HTMLButtonElement).click();
      fixture.detectChanges();
    };

    filter('warn');
    expect(el(fixture).querySelectorAll('.log-line').length).toBe(1);
    expect(text(fixture)).toContain('slow');

    filter('error');
    expect(el(fixture).querySelectorAll('.log-line').length).toBe(0);
    expect(text(fixture)).toContain('No lines at this level');

    filter('info');
    expect(el(fixture).querySelectorAll('.log-line').length).toBe(1);
    expect(text(fixture)).toContain('started');

    filter('all');
    expect(el(fixture).querySelectorAll('.log-line').length).toBe(2);
  });

  it('keeps the tail to the last 40 lines, however many the store holds', () => {
    const { fixture } = render(storeStub({
      logs: Array.from({ length: 60 }, (_, i) => log('info', `line ${i}`)),
    }));

    expect(el(fixture).querySelectorAll('.log-line').length).toBe(40);
  });

  it('counts only the errors inside that window', () => {
    const { fixture } = render(storeStub({
      logs: [
        ...Array.from({ length: 40 }, (_, i) => log('info', `line ${i}`)),
        log('error', 'older than the window'),
      ],
    }));

    expect(text(fixture)).not.toContain('error in tail');
  });
});

describe('OverviewPage image-is-behind notice', () => {
  const catalog = (digest: string | null): Record<string, ImageCatalog> => ({
    'dh-local': {
      repository: 'hermes',
      tags: [{ tag: 'latest', pulled: true, digest }],
      registryStatus: 'ok',
      fetchedAt: 0,
    },
  });

  const onLatest = (imageDigest: string | null): HermesContainer =>
    ({ ...container, version: 'latest', imageDigest });

  it('says so when the registry has moved the tag the container runs', () => {
    // the overview is where an operator lands to ask why an Agent is behaving oddly, and
    // "the image is two months old" is an answer this page should not hide
    const { fixture } = render(storeStub({
      container: onLatest('sha256:aaa'), catalog: catalog('sha256:bbb'),
    }));

    expect(text(fixture)).toContain('update available');
    expect(text(fixture)).toContain('a newer image was published on latest');
  });

  it('stays quiet when the container already runs what the tag points at', () => {
    const { fixture } = render(storeStub({
      container: onLatest('sha256:aaa'), catalog: catalog('sha256:aaa'),
    }));

    expect(text(fixture)).not.toContain('update available');
  });

  it('stays quiet when there is no digest to compare', () => {
    // an air-gapped install must read as "cannot tell", never as an update prompt
    const { fixture } = render(storeStub({
      container: onLatest(null), catalog: catalog('sha256:bbb'),
    }));

    expect(text(fixture)).not.toContain('update available');
  });
});
