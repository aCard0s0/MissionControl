import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { AgentStore } from './core/store/agent-store';
import { ContainerStore } from './core/store/container-store';
import { HostStore } from './core/store/host-store';
import { LiveSync } from './core/store/live-sync';
import { StoreContext } from './core/store/store-context';
import { TerminalRequestStore } from './core/store/terminal-request-store';
import { HermesContainer } from './core/models';
import { button, el, press, settle, text } from './testing/dom';

const container = (id: string, patch: Partial<HermesContainer> = {}): HermesContainer => ({
  id, name: id, shortId: id.slice(0, 4), hostId: 'dh-local', status: 'running',
  image: 'nousresearch/hermes-agent', version: 'v2026.8.3', imageDigest: null, startedAt: 1,
  cpu: 12, ram: 512, ramTotal: 4096, disk: 1, diskTotal: 0, netIn: 0, netOut: 0,
  cpuHist: [], ramHist: [], netHist: [], ...patch,
});

/** Only what the shell and the terminal panel it hosts reach for. */
const storeStub = (containers: HermesContainer[]) => ({
  agents: {
    agents: signal([{ id: 'a-1' }]),
  },
  containers: {
    containers: signal(containers),
    selectedContainerId: signal(containers[0]?.id ?? ''),
    selected: signal(containers[0] ?? null),
    fleetHealth: signal('running'),
    select: vi.fn(),
  },
  ctx: {
    config: { apiBaseUrl: '', dockerSocket: '' },
    liveError: signal<string | null>(null),
    toast: vi.fn(),
  },
  hosts: {
    overall: signal('connected'),
  },
  liveSync: {
    notice: signal<string | null>(null),
  },
  terminal: {
    request: signal(null),
  },
});

const render = (store: ReturnType<typeof storeStub>) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [provideRouter([]), { provide: AgentStore, useValue: store.agents }, { provide: ContainerStore, useValue: store.containers }, { provide: HostStore, useValue: store.hosts }, { provide: LiveSync, useValue: store.liveSync }, { provide: StoreContext, useValue: store.ctx }, { provide: TerminalRequestStore, useValue: store.terminal }],
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  return { fixture, store };
};

describe('App shell', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-19T09:41:07Z'));
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    localStorage.clear();
  });

  it('lists every destination once, in the order the sidebar declares', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    const links = Array.from(el(fixture).querySelectorAll('nav a'))
      .map(a => (a.textContent ?? '').replace(/^\d\d/, '').trim());
    expect(links).toEqual([
      'Containers', 'Overview', 'Agents', 'Blueprints', 'Models',
      'MCP Servers', 'Prompts', 'Ops Board', 'Calendar', 'Webhooks',
      'CLI Reference', 'Server Logs',
    ]);
  });

  it('numbers the tenth destination 10, not 010', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    const indices = Array.from(el(fixture).querySelectorAll('nav a .idx'))
      .map(span => (span.textContent ?? '').trim());
    expect(indices.at(0)).toBe('01');
    expect(indices.at(9)).toBe('10');
  });

  it('shows the fleet counts and the clock in UTC', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    expect(text(fixture)).toContain('1 containers · 1 profiles');
    expect(text(fixture)).toContain('09:41:07 UTC');
    expect(text(fixture)).toContain('WED, 19 AUG 2026');
  });

  it('advances the clock as time passes', async () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    await settle(fixture, 2_000);

    expect(text(fixture)).toContain('09:41:09 UTC');
  });

  it('reports the version the fleet actually runs, not a literal', () => {
    const one = render(storeStub([container('hermes-prod')]));
    expect(text(one.fixture)).toContain('hermes-agent v2026.8.3');

    const mixed = render(storeStub([
      container('hermes-prod'), container('hermes-lab', { version: 'v2026.7.20' })]));
    expect(text(mixed.fixture)).toContain('hermes-agent · 2 versions');

    const none = render(storeStub([]));
    expect(text(none.fixture)).toContain('hermes-agent · no containers');
  });

  it('names the active container, and says so when there is none', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));
    expect(el(fixture).querySelector('.ctx-btn')?.textContent).toContain('hermes-prod');

    const { fixture: empty } = render(storeStub([]));
    expect(el(empty).querySelector('.ctx-btn')?.textContent).toContain('no container');
  });

  it('switches the active container from the picker and closes it', () => {
    const { fixture, store } = render(storeStub([container('hermes-prod'), container('hermes-lab')]));

    el(fixture).querySelector<HTMLButtonElement>('.ctx-btn')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelectorAll('.ctx-row').length).toBe(2);

    el(fixture).querySelectorAll<HTMLButtonElement>('.ctx-row')[1].click();
    fixture.detectChanges();

    expect(store.containers.select).toHaveBeenCalledWith('hermes-lab');
    expect(el(fixture).querySelector('.ctx-pop')).toBeNull();
  });

  it('shows a live notice and an error banner only while the store has one', () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    expect(el(fixture).querySelector('.live-notice')).toBeNull();

    store.liveSync.notice.set('reconnecting to the backend');
    store.ctx.liveError.set('deploy failed: name already in use');
    fixture.detectChanges();

    expect(text(fixture)).toContain('reconnecting to the backend');
    expect(el(fixture).querySelector('.live-notice.crit')?.textContent)
      .toContain('deploy failed: name already in use');
  });

  it('opens the sidebar and closes it again from the scrim', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));
    expect(el(fixture).querySelector('.side-scrim')).toBeNull();

    el(fixture).querySelector<HTMLButtonElement>('.menu-btn')!.click();
    fixture.detectChanges();
    expect(el(fixture).querySelector('aside.side.open')).not.toBeNull();

    el(fixture).querySelector<HTMLElement>('.side-scrim')!.click();
    fixture.detectChanges();

    expect(el(fixture).querySelector('aside.side.open')).toBeNull();
  });
});

describe('App theme', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    localStorage.clear();
  });

  it('starts dark and applies that to the document', () => {
    const { fixture } = render(storeStub([]));

    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(button(fixture, '☀')).not.toBeNull();
  });

  it('toggles the theme and remembers it for the next visit', () => {
    const { fixture } = render(storeStub([]));

    press(fixture, '☀');

    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem('mc-theme')).toBe('light');
  });

  it('restores the saved theme on load', () => {
    localStorage.setItem('mc-theme', 'light');

    render(storeStub([]));

    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('falls back to dark when storage is unreadable, as in private mode', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem')
      .mockImplementation(() => { throw new Error('access denied'); });
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
      .mockImplementation(() => { throw new Error('access denied'); });

    render(storeStub([]));

    expect(document.documentElement.dataset['theme']).toBe('dark');
    getItem.mockRestore();
    setItem.mockRestore();
  });
});

describe('App shell sidebar', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  const host = (fixture: { nativeElement: unknown }) => el(fixture);
  const collapse = (fixture: { nativeElement: unknown }) =>
    (host(fixture).querySelector('.collapse-btn') as HTMLButtonElement);

  it('collapses the sidebar and brings it back', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    expect(host(fixture).classList.contains('side-collapsed')).toBe(false);

    collapse(fixture).click();
    fixture.detectChanges();
    expect(host(fixture).classList.contains('side-collapsed')).toBe(true);

    collapse(fixture).click();
    fixture.detectChanges();
    expect(host(fixture).classList.contains('side-collapsed')).toBe(false);
  });

  it('remembers the choice, because it is a preference and not a mode', () => {
    const first = render(storeStub([container('hermes-prod')]));
    collapse(first.fixture).click();
    first.fixture.detectChanges();
    expect(localStorage.getItem('mc-side-collapsed')).toBe('true');

    // a reload lands on the same layout rather than reopening what was put away
    const second = render(storeStub([container('hermes-prod')]));
    expect(host(second.fixture).classList.contains('side-collapsed')).toBe(true);
  });

  it('says which way it will go, for anyone not reading the icon', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    expect(collapse(fixture).getAttribute('aria-label')).toBe('hide sidebar');
    expect(collapse(fixture).getAttribute('aria-expanded')).toBe('true');

    collapse(fixture).click();
    fixture.detectChanges();

    expect(collapse(fixture).getAttribute('aria-label')).toBe('show sidebar');
    expect(collapse(fixture).getAttribute('aria-expanded')).toBe('false');
  });

  it('keeps the collapse out of the drawer\'s way', () => {
    // Closing the drawer must not put the sidebar away, or every nav item — which closes it
    // through this same handler — would undo a collapse the operator chose to keep. Driven
    // through the scrim rather than a nav link, because navigating here would need the real
    // route table and the assertion is about the two states, not about routing.
    const { fixture } = render(storeStub([container('hermes-prod')]));

    (host(fixture).querySelector('.menu-btn') as HTMLButtonElement).click();
    fixture.detectChanges();
    collapse(fixture).click();
    fixture.detectChanges();

    const scrim = host(fixture).querySelector('.side-scrim') as HTMLElement;
    expect(scrim).not.toBeNull();
    scrim.click();
    fixture.detectChanges();

    expect(host(fixture).querySelector('.side-scrim')).toBeNull();   // drawer closed
    expect(host(fixture).classList.contains('side-collapsed')).toBe(true);
  });
});

