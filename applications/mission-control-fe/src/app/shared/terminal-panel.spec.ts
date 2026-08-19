import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from '../core/hermes-store';
import { HermesContainer } from '../core/models';
import { TerminalPanel } from './terminal-panel';
import { el, settle, text } from '../testing/dom';
import { container } from '../testing/models';

/**
 * Records every shell the panel opens. The real xterm runs here — it only needs
 * the browser APIs stubbed in {@link liveShells} — so these tests are about what
 * the panel multiplexes: which tabs exist, which is on screen, and which socket
 * is live. What travels over that socket is terminal-session.spec.ts' subject.
 */
class FakeSocket {
  static opened: FakeSocket[] = [];
  static readonly OPEN = 1;

  readyState = 1;
  binaryType = '';
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(readonly url: string) {
    FakeSocket.opened.push(this);
  }

  send(): void { /* asserted in terminal-session.spec.ts */ }

  close(): void {
    this.closed = true;
    this.readyState = 3;
    this.onclose?.();
  }
}

/** Only what the panel reaches for on the store. */
const storeStub = (containers: HermesContainer[] = [], selected: HermesContainer | null = null) => ({
  config: { apiBaseUrl: '', dockerSocket: '' },
  terminalRequest: signal<unknown>(null),
  containers: signal(containers),
  selectedContainer: signal(selected),
  hostById: (id: string) => ({ id, name: 'localhost' }),
  toast: vi.fn(),
});

/**
 * The browser pieces a live shell needs and jsdom does not provide: a socket to
 * open, a resize observer for the host div, and the media query xterm's style
 * lookup goes through. Fake timers cover the panel's queued fit/focus.
 */
const liveShells = (): void => {
  beforeEach(() => {
    vi.useFakeTimers();
    localStorage.clear();
    FakeSocket.opened = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.stubGlobal('ResizeObserver', class {
      observe(): void { /* no layout in jsdom */ }
      disconnect(): void { /* no layout in jsdom */ }
    });
    // xterm watches the device-pixel-ratio query through the legacy listener API
    vi.stubGlobal('matchMedia', (media: string) => ({
      media, matches: false,
      addListener: () => { /* the dpr never changes here */ },
      removeListener: () => { /* the dpr never changes here */ },
      addEventListener: () => { /* the dpr never changes here */ },
      removeEventListener: () => { /* the dpr never changes here */ },
    }));
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });
};

const render = (store: ReturnType<typeof storeStub> = storeStub()) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: HermesStore, useValue: store }] });
  const fixture = TestBed.createComponent(TerminalPanel);
  fixture.detectChanges();
  return { fixture, store };
};

type Fixture = ReturnType<typeof render>['fixture'];

const openPanel = async (fixture: Fixture): Promise<void> => {
  el(fixture).querySelector<HTMLElement>('.bar')!.click();
  await settle(fixture);
};

const tabs = (fixture: Fixture): HTMLElement[] =>
  Array.from(el(fixture).querySelectorAll<HTMLElement>('.tab'));

const labels = (fixture: Fixture): string[] =>
  tabs(fixture).map(t => (t.querySelector('.lbl')?.textContent ?? '').trim());

/** Writes the tab state a previous visit would have left behind. */
const savedTabs = (tabList: unknown[], activeId: string | null): void =>
  localStorage.setItem('mc-terminal-tabs', JSON.stringify({ v: 1, tabs: tabList, activeId }));

describe('TerminalPanel closed', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('starts collapsed, with nothing attached yet', () => {
    const { fixture } = render();

    expect(text(fixture)).toContain('TERMINAL');
    expect(text(fixture)).toContain('no shell');
    expect(el(fixture).querySelector('.body')).toBeNull();
    expect(el(fixture).querySelector('.tabs')).toBeNull();
  });

  it('offers no shell controls while it is closed', () => {
    const { fixture } = render();

    expect(el(fixture).querySelector('button[title="restart session"]')).toBeNull();
    expect(el(fixture).querySelector('button[title="taller"]')).toBeNull();
  });

  it('restores nothing when there are no saved tabs', () => {
    const { fixture } = render();

    expect(tabs(fixture).length).toBe(0);
  });
});

describe('TerminalPanel tabs', () => {
  liveShells();

  it('seeds a shell on the active container the first time it opens', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));

    await openPanel(fixture);

    expect(labels(fixture)).toEqual(['hermes-prod']);
    expect(FakeSocket.opened[0].url)
      .toContain('/ws/terminal?hostId=dh-local&containerId=hermes-prod');
  });

  it('opens an unconfigured tab with a picker when no container is active', async () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));

    await openPanel(fixture);

    expect(labels(fixture)).toEqual(['(choose)']);
    expect(el(fixture).querySelector('.pop')).not.toBeNull();
    expect(FakeSocket.opened).toEqual([]);        // nothing to connect to yet
  });

  it('points an unconfigured tab at the container the operator picks', async () => {
    const { fixture } = render(storeStub([container('hermes-prod')]));
    await openPanel(fixture);

    el(fixture).querySelector<HTMLButtonElement>('.pop .row')!.click();
    await settle(fixture);

    expect(labels(fixture)).toEqual(['hermes-prod']);
    expect(FakeSocket.opened.length).toBe(1);
    expect(el(fixture).querySelector('.pop')).toBeNull();
  });

  it('says so rather than showing an empty picker with no containers', async () => {
    const { fixture } = render(storeStub([]));

    await openPanel(fixture);

    expect(text(fixture)).toContain('no containers');
  });

  it('adds a second shell in the same container and focuses it', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(2);
    expect(tabs(fixture)[1].classList).toContain('act');
    expect(FakeSocket.opened.length).toBe(2);     // two independent execs
  });

  it('refuses to open more shells than the panel allows, and says why', async () => {
    const c = container('hermes-prod');
    const { fixture, store } = render(storeStub([c], c));
    await openPanel(fixture);

    for (let i = 0; i < 12; i++) {
      el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
      await settle(fixture);
    }

    expect(tabs(fixture).length).toBe(12);
    expect(store.toast).toHaveBeenCalledWith(
      'terminal tab limit (12) reached — close a tab first');
  });

  it('moves focus to the neighbour when the active tab is closed', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    tabs(fixture)[1].querySelector<HTMLButtonElement>('.x')!.click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
    expect(tabs(fixture)[0].classList).toContain('act');
    expect(FakeSocket.opened[1].closed).toBe(true);
  });

  it('switches which shell is on screen', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    tabs(fixture)[0].click();
    await settle(fixture);

    expect(tabs(fixture)[0].classList).toContain('act');
    expect(tabs(fixture)[1].classList).not.toContain('act');
  });

  it('follows a container rename, because the label is only a snapshot', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);
    await openPanel(fixture);

    store.containers.set([container('hermes-prod', { name: 'hermes-renamed' })]);
    await settle(fixture);

    expect(labels(fixture)).toEqual(['hermes-renamed']);
  });

  it('repoints a tab from its own picker, without stacking another', async () => {
    const prod = container('hermes-prod');
    const { fixture } = render(storeStub([prod, container('hermes-lab')], prod));
    await openPanel(fixture);

    tabs(fixture)[0].querySelector<HTMLButtonElement>('.caret')!.click();
    await settle(fixture);
    expect(el(fixture).querySelector('.pop')).not.toBeNull();

    el(fixture).querySelectorAll<HTMLButtonElement>('.pop .row')[1].click();
    await settle(fixture);

    expect(labels(fixture)).toEqual(['hermes-lab']);
    expect(FakeSocket.opened.length).toBe(2);   // the same tab, re-exec'd elsewhere
  });

  it('closes the picker again from the caret', async () => {
    const prod = container('hermes-prod');
    const { fixture } = render(storeStub([prod], prod));
    await openPanel(fixture);
    const caret = () => tabs(fixture)[0].querySelector<HTMLButtonElement>('.caret')!;

    caret().click();
    await settle(fixture);
    caret().click();
    await settle(fixture);

    expect(el(fixture).querySelector('.pop')).toBeNull();
  });

  it('drags the top edge to resize, and remembers where it was let go', async () => {
    const prod = container('hermes-prod');
    const { fixture } = render(storeStub([prod], prod));
    await openPanel(fixture);

    el(fixture).querySelector<HTMLElement>('.drag')!
      .dispatchEvent(new PointerEvent('pointerdown', { clientY: 500, bubbles: true }));
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 460 }));
    window.dispatchEvent(new PointerEvent('pointerup'));
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLElement>('.body')!.style.height).toBe('320px');
    expect(localStorage.getItem('mc-terminal-height')).toBe('320');
  });

  it('drops the socket of a tab whose container left the inventory, keeping the tab', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);
    await openPanel(fixture);

    store.containers.set([]);
    await settle(fixture);

    expect(FakeSocket.opened[0].closed).toBe(true);
    expect(tabs(fixture).length).toBe(1);
    expect(tabs(fixture)[0].querySelector('.lbl')?.classList).toContain('gone');
  });
});

describe('TerminalPanel restore', () => {
  liveShells();

  it('brings back the tabs and the one that was on screen', async () => {
    savedTabs([
      { id: 't-1', hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod' },
      { id: 't-2', hostId: 'dh-local', containerId: 'hermes-lab', label: 'hermes-lab' },
    ], 't-2');
    const { fixture } = render(storeStub([container('hermes-prod'), container('hermes-lab')]));

    await openPanel(fixture);

    expect(labels(fixture)).toEqual(['hermes-prod', 'hermes-lab']);
    expect(tabs(fixture)[1].classList).toContain('act');
    expect(FakeSocket.opened.length).toBe(2);   // each restored tab is a fresh exec
  });

  it('keeps an agent tab on its profile name, not on the container it runs in', async () => {
    savedTabs([{
      id: 't-1', hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot',
      agentKey: 'a-ops', command: 'hermes -p ops-bot',
    }], 't-1');
    const { fixture } = render(storeStub([container('hermes-prod')]));

    await openPanel(fixture);

    expect(labels(fixture)).toEqual(['ops-bot']);
  });

  it('writes back only the tabs that could be restored', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    const saved = JSON.parse(localStorage.getItem('mc-terminal-tabs')!);
    expect(saved.v).toBe(1);
    expect(saved.tabs.map((t: { containerId: string }) => t.containerId))
      .toEqual(['hermes-prod', 'hermes-prod']);
  });
});

describe('TerminalPanel requests from other pages', () => {
  liveShells();

  it('opens the panel and seeds a shell for an untargeted request', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);

    store.terminalRequest.set({ seq: 1 });
    await settle(fixture);

    expect(el(fixture).querySelector('.tabs')).not.toBeNull();
    expect(labels(fixture)).toEqual(['hermes-prod']);
  });

  it('pins a targeted request to the container it named', async () => {
    const store = storeStub([container('hermes-prod'), container('hermes-lab')]);
    const { fixture } = render(store);

    store.terminalRequest.set({
      seq: 1, hostId: 'dh-local', containerId: 'hermes-lab', label: 'ops-bot',
      agentKey: 'a-ops', command: 'hermes -p ops-bot',
    });
    await settle(fixture);

    expect(labels(fixture)).toEqual(['ops-bot']);
    expect(FakeSocket.opened[0].url).toContain('containerId=hermes-lab');
  });

  it('focuses the shell an agent already has instead of stacking another', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    const request = {
      seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot', agentKey: 'a-ops',
    };
    store.terminalRequest.set(request);
    await settle(fixture);

    store.terminalRequest.set({ ...request, seq: 2 });
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
    expect(FakeSocket.opened.length).toBe(1);
  });

  it('revives an agent shell the operator had let close', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    const request = {
      seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot', agentKey: 'a-ops',
    };
    store.terminalRequest.set(request);
    await settle(fixture);
    FakeSocket.opened[0].close();
    await settle(fixture);

    store.terminalRequest.set({ ...request, seq: 2 });
    await settle(fixture);

    expect(FakeSocket.opened.length).toBe(2);
  });

  it('acts on a request exactly once, however often change detection runs', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);

    store.terminalRequest.set({ seq: 1 });
    await settle(fixture);
    fixture.detectChanges();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
  });
});

describe('TerminalPanel height', () => {
  liveShells();

  it('grows and shrinks the panel, and remembers the size', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    const body = () => el(fixture).querySelector<HTMLElement>('.body')!.style.height;

    el(fixture).querySelector<HTMLButtonElement>('button[title="taller"]')!.click();
    await settle(fixture);
    expect(body()).toBe('360px');

    el(fixture).querySelector<HTMLButtonElement>('button[title="shorter"]')!.click();
    await settle(fixture);
    expect(body()).toBe('280px');
    expect(localStorage.getItem('mc-terminal-height')).toBe('280');
  });

  it('restarts and clears the shell on screen', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    el(fixture).querySelector<HTMLButtonElement>('button[title="restart session"]')!.click();
    await settle(fixture);
    expect(FakeSocket.opened.length).toBe(2);
    expect(FakeSocket.opened[0].closed).toBe(true);

    el(fixture).querySelector<HTMLButtonElement>('button[title="clear"]')!.click();
    await settle(fixture);
  });

  it('releases every exec when the page goes away', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    window.dispatchEvent(new Event('pagehide'));

    expect(FakeSocket.opened[0].closed).toBe(true);
  });

  it('releases every exec when the shell itself is torn down', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    fixture.destroy();

    expect(FakeSocket.opened[0].closed).toBe(true);
  });
});
