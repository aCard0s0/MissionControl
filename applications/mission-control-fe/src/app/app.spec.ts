import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './app';
import { ActivityStore } from './core/store/activity-store';
import { HermesContainer } from './core/models';
import { button, el, press, settle, text } from './testing/dom';
import { provideStores } from './testing/store';

const container = (id: string, patch: Partial<HermesContainer> = {}): HermesContainer => ({
  id, name: id, shortId: id.slice(0, 4), hostId: 'dh-local', status: 'running',
  image: 'nousresearch/hermes-agent', version: 'v2026.8.3', imageDigest: null, release: null, startedAt: 1,
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
    toasts: signal<{ id: number; kind: 'ok' | 'error'; message: string; at: number }[]>([]),
    toast: vi.fn(),
    notify: vi.fn(),
    dismiss: vi.fn(),
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
    providers: [provideRouter([]), ...provideStores(store)],
  });
  const fixture = TestBed.createComponent(App);
  fixture.detectChanges();
  // the real one: it holds no dependencies, and a stub could not prove the strip ticks
  return { fixture, store, activity: TestBed.inject(ActivityStore) };
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
      .map(a => (a.textContent ?? '').trim());
    expect(links).toEqual([
      'Containers', 'Overview', 'Agents', 'Blueprints', 'Models', 'Credentials',
      'MCP Servers', 'Prompts', 'Skills', 'Ops Board', 'Calendar', 'Webhooks',
      'CLI Reference', 'Server Logs',
    ]);
  });

  it('gives every destination its own glyph, and no numbers', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    const links = Array.from(el(fixture).querySelectorAll('nav a'));
    const drawn = links.map(a => a.querySelectorAll('mc-nav-icon svg path, mc-nav-icon svg rect, mc-nav-icon svg circle').length);
    expect(drawn.every(n => n > 0)).toBe(true);
    expect(links.map(a => (a.textContent ?? '').trim()).join(' ')).not.toMatch(/\d/);
  });

  it('shows the fleet counts and the clock in UTC', () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    expect(text(fixture)).toContain('1 container · 1 profile');
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

    // a pointer is not a version: the release hermes reports is what the fleet runs
    const floating = render(storeStub([container('hermes-prod', { version: 'latest', release: '2026.8.19' })]));
    expect(text(floating.fixture)).toContain('hermes-agent v2026.8.19');
    expect(text(floating.fixture)).not.toContain('latest');
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

  it('keeps the standing-condition banner, whose place is the page and not the stack', () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    expect(el(fixture).querySelector('.live-notice')).toBeNull();

    store.liveSync.notice.set('reconnecting to the backend');
    fixture.detectChanges();

    expect(text(fixture)).toContain('reconnecting to the backend');
  });

  it('hosts the notification stack, so an operation survives leaving the page', () => {
    const { fixture, activity } = render(storeStub([container('hermes-prod')]));
    expect(el(fixture).querySelector('mc-notifications .stack')).toBeNull();

    activity.begin('deploying ops-bot');
    fixture.detectChanges();

    expect(text(fixture)).toContain('deploying ops-bot');
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

