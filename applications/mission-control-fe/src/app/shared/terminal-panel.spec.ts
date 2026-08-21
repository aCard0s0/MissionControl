import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ContainerStore } from '../core/store/container-store';
import { HostStore } from '../core/store/host-store';
import { StoreContext } from '../core/store/store-context';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
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

  readonly sent: string[] = [];

  send(data: ArrayBufferView | string): void {
    this.sent.push(typeof data === 'string' ? data : new TextDecoder().decode(data as Uint8Array));
  }

  close(): void {
    this.closed = true;
    this.readyState = 3;
    this.onclose?.();
  }
}

/** Only what the panel reaches for on the store. */
const storeStub = (containers: HermesContainer[] = [], selected: HermesContainer | null = null) => ({
  containers: {
    containers: signal(containers),
    selected: signal(selected),
  },
  ctx: {
    config: { apiBaseUrl: '', dockerSocket: '' },
    toast: vi.fn(),
  },
  hosts: {
    byId: (id: string) => ({ id, name: 'localhost' }),
  },
  terminal: {
    request: signal<unknown>(null),
  },
});

/**
 * The browser pieces a live shell needs and jsdom does not provide: a socket to
 * open, a resize observer for the host div, and the media query xterm's style
 * lookup goes through. Fake timers cover the panel's queued fit/focus.
 */
const liveShells = (): void => {
  beforeEach(async () => {
    // The dock is a dynamic import in the panel. Fetching a module for the first
    // time needs the real event loop, which fake timers do not drive — so it is
    // pulled into the registry here, before they are installed. Every in-test
    // import then resolves from cache, on microtasks the specs can flush.
    await import('./terminal-dock');
    vi.useFakeTimers();
    localStorage.clear();
    FakeSocket.opened = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.stubGlobal('ResizeObserver', class {
      observe(): void { /* no layout in jsdom */ }
      unobserve(): void { /* the dock stops watching panes it removes */ }
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
  TestBed.configureTestingModule({ providers: [{ provide: ContainerStore, useValue: store.containers }, { provide: HostStore, useValue: store.hosts }, { provide: StoreContext, useValue: store.ctx }, { provide: TerminalRequestStore, useValue: store.terminal }] });
  const fixture = TestBed.createComponent(TerminalPanel);
  fixture.detectChanges();
  return { fixture, store };
};

type Fixture = ReturnType<typeof render>['fixture'];

/**
 * Presses the bar, then waits for the dock to land.
 *
 * The dock is a lazily imported chunk, so the shells arrive some ticks after the
 * body they sit in — and how many is not a number worth hard-coding. This waits
 * for the dock itself, and only when the press opened the panel rather than
 * collapsing it.
 */
const openPanel = async (fixture: Fixture): Promise<void> => {
  el(fixture).querySelector<HTMLElement>('.bar')!.click();
  await settle(fixture);
  if (!el(fixture).querySelector('.body')) return;   // that press collapsed it
  for (let i = 0; i < 100 && !el(fixture).querySelector('.dv-dockview'); i++) {
    await settle(fixture);
  }
  await settle(fixture);
};

const tabs = (fixture: Fixture): HTMLElement[] =>
  Array.from(el(fixture).querySelectorAll<HTMLElement>('.tab'));

const labels = (fixture: Fixture): string[] =>
  tabs(fixture).map(t => (t.querySelector('.lbl')?.textContent ?? '').trim());

/** Writes the tab state a previous visit would have left behind. */
const savedTabs = (tabList: unknown[], activeId: string | null, layout: unknown = null): void =>
  localStorage.setItem('mc-terminal-tabs',
    JSON.stringify({ v: 2, tabs: tabList, activeId, layout }));

/** The groups the dock is showing, and the handles between them. */
const groups = (fixture: Fixture): number =>
  el(fixture).querySelectorAll('.dv-groupview').length;

/** The drag handles between groups. Counted, not driven: whether a sash is
 *  enabled depends on a measured layout, and jsdom gives elements no box. */
const sashes = (fixture: Fixture): number =>
  el(fixture).querySelectorAll('.dv-sash').length;

describe('TerminalPanel closed', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  it('starts collapsed, with nothing attached yet', () => {
    const { fixture } = render();

    expect(text(fixture)).toContain('TERMINAL');
    expect(text(fixture)).toContain('no shell');
    expect(el(fixture).querySelector('.body')).toBeNull();
    expect(el(fixture).querySelector('.tab')).toBeNull();
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

    // the cap is enforced as each tab is added, so the clicks need no repaint
    // between them — one settle at the end is both enough and much cheaper
    const add = el(fixture).querySelector<HTMLButtonElement>('.add')!;
    for (let i = 0; i < 12; i++) add.click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(12);
    expect(store.ctx.toast).toHaveBeenCalledWith(
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

    store.containers.containers.set([container('hermes-prod', { name: 'hermes-renamed' })]);
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

    store.containers.containers.set([]);
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
    await settle(fixture, 300);   // the arrangement is written once the layout settles

    const saved = JSON.parse(localStorage.getItem('mc-terminal-tabs')!);
    expect(saved.v).toBe(2);
    expect(saved.tabs.map((t: { containerId: string }) => t.containerId))
      .toEqual(['hermes-prod', 'hermes-prod']);
    // and the arrangement they were in, so a reload comes back to the same splits
    expect(Object.keys(saved.layout.panels).length).toBe(2);
  });
});

describe('TerminalPanel requests from other pages', () => {
  liveShells();

  it('opens the panel and seeds a shell for an untargeted request', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);

    store.terminal.request.set({ seq: 1 });
    await settle(fixture);

    expect(el(fixture).querySelector('.tab')).not.toBeNull();
    expect(labels(fixture)).toEqual(['hermes-prod']);
  });

  it('pins a targeted request to the container it named', async () => {
    const store = storeStub([container('hermes-prod'), container('hermes-lab')]);
    const { fixture } = render(store);

    store.terminal.request.set({
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
    store.terminal.request.set(request);
    await settle(fixture);

    store.terminal.request.set({ ...request, seq: 2 });
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
    expect(FakeSocket.opened.length).toBe(1);
  });

  it('focuses the shell a container already has instead of stacking another', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    const request = { seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod' };
    store.terminal.request.set(request);
    await settle(fixture);

    store.terminal.request.set({ ...request, seq: 2 });
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
    expect(FakeSocket.opened.length).toBe(1);
  });

  it('gives a container shell its own tab rather than an agent tab in the same container', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    store.terminal.request.set({
      seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot',
      agentKey: 'a-ops', command: 'hermes -p ops-bot',
    });
    await settle(fixture);

    // that prompt is inside `hermes session` — a container shell is not the same shell
    store.terminal.request.set({
      seq: 2, hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod',
    });
    await settle(fixture);

    expect(labels(fixture)).toEqual(['ops-bot', 'hermes-prod']);
  });

  it('revives an agent shell the operator had let close', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    const request = {
      seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot', agentKey: 'a-ops',
    };
    store.terminal.request.set(request);
    await settle(fixture);
    FakeSocket.opened[0].close();
    await settle(fixture);

    store.terminal.request.set({ ...request, seq: 2 });
    await settle(fixture);

    expect(FakeSocket.opened.length).toBe(2);
  });

  it('acts on a request exactly once, however often change detection runs', async () => {
    const c = container('hermes-prod');
    const store = storeStub([c], c);
    const { fixture } = render(store);

    store.terminal.request.set({ seq: 1 });
    await settle(fixture);
    fixture.detectChanges();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
  });
});

describe('TerminalPanel command drawer', () => {
  liveShells();

  const cmds = (fixture: Fixture): HTMLElement =>
    el(fixture).querySelector<HTMLElement>('button[title="hermes commands"]')!;

  /** What actually reached the shell's stdin — resize frames are control traffic, not typing. */
  const typed = (socket: FakeSocket): string[] =>
    socket.sent.filter(frame => !frame.startsWith('{"type":'));

  /** Clicks the insert action on the rail's row for this command line. Found by its
   *  aria-label, not its text: in the rail the actions are glyphs. */
  const insert = async (fixture: Fixture, line: string): Promise<void> => {
    const row = Array.from(el(fixture).querySelectorAll<HTMLElement>('.cheat .cmd'))
      .find(r => (r.querySelector('.line')?.textContent ?? '').trim() === line);
    if (!row) throw new Error(`no rail row for "${line}"`);
    row.querySelector<HTMLButtonElement>('button.act[aria-label="insert"]')!.click();
    await settle(fixture);
  };

  it('stays out of the way until asked for', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    expect(el(fixture).querySelector('.cheat')).toBeNull();
  });

  it('opens the panel with it, because a reference with no prompt under it is a web page', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));

    cmds(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector('.cheat')).not.toBeNull();
    expect(el(fixture).querySelector('.body')).not.toBeNull();
    expect(text(fixture)).toContain('hermes cron');
  });

  it('closes again', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    cmds(fixture).click();
    await settle(fixture);

    cmds(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector('.cheat')).toBeNull();
  });

  it('types the line at the prompt with no newline — the operator runs it', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    cmds(fixture).click();
    await settle(fixture);

    await insert(fixture, 'hermes status');

    expect(typed(FakeSocket.opened[0])).toEqual(['hermes status']);
  });

  it('scopes the lines to the profile the active tab is a shell for', async () => {
    const store = storeStub([container('hermes-prod')]);
    const { fixture } = render(store);
    store.terminal.request.set({
      seq: 1, hostId: 'dh-local', containerId: 'hermes-prod', label: 'ops-bot',
      agentKey: 'a-ops',
    });
    await settle(fixture);

    cmds(fixture).click();
    await settle(fixture);

    await insert(fixture, 'hermes -p ops-bot cron');
    expect(typed(FakeSocket.opened[0])).toEqual(['hermes -p ops-bot cron']);
  });

  it('leaves a plain container shell unscoped — it is not running any one profile', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    cmds(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector('.cheat')!.textContent).not.toContain('-p ');
  });

  it('says so rather than swallowing a line when the tab has no container yet', async () => {
    const { fixture, store } = render(storeStub());   // nothing selected — the tab opens unbound
    await openPanel(fixture);
    cmds(fixture).click();
    await settle(fixture);

    await insert(fixture, 'hermes status');

    expect(store.ctx.toast).toHaveBeenCalledWith(
      'no live shell — pick a container or reconnect the tab first');
    expect(FakeSocket.opened).toEqual([]);
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

/**
 * The point of the dock: two or more shells on screen at once, in groups you can
 * resize against each other. Everything above is about which shells exist; this
 * is about where they sit.
 */
describe('TerminalPanel splits', () => {
  liveShells();

  const splitBtn = (fixture: Fixture): HTMLButtonElement =>
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!;

  it('puts a new shell beside the current one rather than behind it', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    expect(groups(fixture)).toBe(1);

    splitBtn(fixture).click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(2);
    expect(groups(fixture)).toBe(2);            // side by side...
    expect(sashes(fixture)).toBe(1);            // ...with a handle between them
    expect(FakeSocket.opened.length).toBe(2);   // two independent execs
  });

  it('keeps both shells attached and live, so neither stops streaming', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture);

    // both terminals are in the document — a pane out of view is hidden by the
    // dock, never torn out, which is what lets its buffer go on filling
    expect(el(fixture).querySelectorAll('.xterm-host').length).toBe(2);
    expect(FakeSocket.opened.map(s => s.closed)).toEqual([false, false]);
  });

  it('leaves + stacking tabs in one group, which is the other half of the offer', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(2);
    expect(groups(fixture)).toBe(1);
    expect(sashes(fixture)).toBe(0);
  });

  it('acts on the pane that has focus, not on whichever came first', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture);

    el(fixture).querySelector<HTMLButtonElement>('button[title="restart session"]')!.click();
    await settle(fixture);

    expect(FakeSocket.opened.length).toBe(3);          // the focused pane re-exec'd
    expect(FakeSocket.opened[1].closed).toBe(true);
    expect(FakeSocket.opened[0].closed).toBe(false);   // its neighbour untouched
  });

  it('collapses back to one group when a split pane is closed', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture);

    tabs(fixture)[1].querySelector<HTMLButtonElement>('.x')!.click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(1);
    expect(groups(fixture)).toBe(1);
    expect(sashes(fixture)).toBe(0);
    expect(FakeSocket.opened[1].closed).toBe(true);
  });

  it('comes back to the same arrangement after a reload', async () => {
    const c = container('hermes-prod');
    const first = render(storeStub([c], c));
    await openPanel(first.fixture);
    splitBtn(first.fixture).click();
    await settle(first.fixture, 300);           // let the arrangement be written down
    first.fixture.destroy();

    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    expect(tabs(fixture).length).toBe(2);
    expect(groups(fixture)).toBe(2);
    expect(sashes(fixture)).toBe(1);
  });

  it('keeps the splits across the panel being collapsed and opened again', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture);
    const live = FakeSocket.opened.length;

    await openPanel(fixture);                   // collapse
    expect(el(fixture).querySelector('.body')).toBeNull();
    await openPanel(fixture);                   // and open again

    expect(groups(fixture)).toBe(2);
    // closing the panel is not closing a shell: the same execs are still there
    expect(FakeSocket.opened.length).toBe(live);
    expect(FakeSocket.opened.every(s => !s.closed)).toBe(true);
  });

  it('tells the focused pane apart from one merely on screen', async () => {
    // Three states, because a split broke the old equivalence: the focused tab,
    // a tab on screen in the *other* group, and a tab buried in a stack. The
    // screenshot that prompted this had all three looking the same.
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();   // a stacked tab
    await settle(fixture);
    splitBtn(fixture).click();                                       // and a split one
    await settle(fixture);

    const state = (t: HTMLElement) =>
      `${t.classList.contains('act') ? 'act' : ''}${t.classList.contains('vis') ? ' vis' : ''}`
        .trim() || 'stacked';

    // tab 0 is hidden behind tab 1 in the left group; tab 1 shows there but the
    // right group has the keyboard; tab 2 is the focused pane
    expect(tabs(fixture).map(state)).toEqual(['stacked', 'vis', 'act vis']);

    tabs(fixture)[1].click();
    await settle(fixture);

    // focus moved back to the left group, so the two swap roles
    expect(tabs(fixture).map(state)).toEqual(['stacked', 'act vis', 'vis']);
  });

  it('brings dockview\'s stylesheet with it, which its bundler entry does not', async () => {
    // dockview-core ships no .css file: only its bundled build appends the
    // stylesheet to the document. Importing the wrong entry still renders a dock
    // — an unstyled one, with no sashes and no tab strip, which no assertion
    // about structure would catch. So the stylesheet itself is the assertion.
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    const shell = el(fixture).querySelector('.dv-shell');
    expect(shell?.className).toContain('dockview-theme-dark');
    expect(Array.from(document.querySelectorAll('style'))
      .some(tag => (tag.textContent ?? '').includes('.dv-tab'))).toBe(true);
  });

  it('writes the arrangement once the layout settles, not once per drag frame', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture);

    // every one of those writes is a JSON.stringify of the whole grid into
    // synchronous localStorage, and a sash drag reports a change per frame
    const writes = vi.spyOn(Storage.prototype, 'setItem');
    splitBtn(fixture).click();
    await settle(fixture);
    const beforeSettling = writes.mock.calls.filter(([key]) => key === 'mc-terminal-tabs').length;

    await settle(fixture, 300);
    const afterSettling = writes.mock.calls.filter(([key]) => key === 'mc-terminal-tabs').length;

    expect(afterSettling).toBe(beforeSettling + 1);
    writes.mockRestore();
  });

  it('writes a rearrangement the operator collapsed the panel on top of', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture, 300);
    localStorage.removeItem('mc-terminal-tabs');

    // a change and a collapse inside the save's settling window: cancelling the timer
    // also cancels the nudge the persistence effect waits on, so the panel has to
    // finish the write itself rather than drop it
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);
    await openPanel(fixture);                   // collapse, well inside the 250ms

    const saved = JSON.parse(localStorage.getItem('mc-terminal-tabs')!);
    expect(Object.keys(saved.layout.panels).length).toBe(3);
  });

  it('writes a rearrangement the panel was torn down on top of', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    splitBtn(fixture).click();
    await settle(fixture, 300);
    localStorage.removeItem('mc-terminal-tabs');

    // same window as the collapse case, but reached by destroy — both routes have to
    // flush, which is why the save lives in one helper rather than in unmountDock
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);
    fixture.destroy();

    const saved = JSON.parse(localStorage.getItem('mc-terminal-tabs')!);
    expect(Object.keys(saved.layout.panels).length).toBe(3);
  });

  it('restores a saved arrangement rather than stacking the tabs it names', async () => {
    savedTabs(
      [
        { id: 't-1', hostId: 'dh-local', containerId: 'hermes-prod', label: 'hermes-prod' },
        { id: 't-2', hostId: 'dh-local', containerId: 'hermes-lab', label: 'hermes-lab' },
      ],
      't-2',
      {
        grid: {
          orientation: 'HORIZONTAL', width: 800, height: 300,
          root: {
            type: 'branch',
            data: [
              { type: 'leaf', size: 400, data: { id: 'g-1', views: ['t-1'], activeView: 't-1' } },
              { type: 'leaf', size: 400, data: { id: 'g-2', views: ['t-2'], activeView: 't-2' } },
            ],
          },
        },
        panels: {
          't-1': { id: 't-1', contentComponent: 'mc-terminal', tabComponent: 'mc-terminal-tab' },
          't-2': { id: 't-2', contentComponent: 'mc-terminal', tabComponent: 'mc-terminal-tab' },
        },
        activeGroup: 'g-2',
      });
    const { fixture } = render(storeStub([container('hermes-prod'), container('hermes-lab')]));

    await openPanel(fixture);

    expect(labels(fixture)).toEqual(['hermes-prod', 'hermes-lab']);
    expect(groups(fixture)).toBe(2);
    expect(FakeSocket.opened.length).toBe(2);
  });
});

/**
 * Splitting right halves the columns, and a shell whose output is wider than
 * that wraps — hermes' skill lists are wide enough to break their own ASCII box
 * at half a panel. Splitting down keeps every column instead. Both exist because
 * which trade is right depends on what is being read.
 */
describe('TerminalPanel split direction', () => {
  liveShells();

  const splitBtn = (fixture: Fixture): HTMLButtonElement =>
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!;

  const stackBtn = (fixture: Fixture): HTMLButtonElement =>
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split down"]')!;

  /** The top of the grid dockview serialized. Splitting right puts two leaves
   *  there, sharing the width; splitting down nests one full-width branch and
   *  divides the height inside it. That shape *is* the columns-vs-rows trade. */
  const gridTop = (): { type: string; kids: number } => {
    const saved = JSON.parse(localStorage.getItem('mc-terminal-tabs')!);
    const kids = saved.layout.grid.root.data as { type: string }[];
    return { type: kids.map(k => k.type).join('+'), kids: kids.length };
  };

  it('stacks a shell under the current one, keeping the full width', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    stackBtn(fixture).click();
    await settle(fixture);

    expect(tabs(fixture).length).toBe(2);
    expect(groups(fixture)).toBe(2);
    expect(sashes(fixture)).toBe(1);
    expect(FakeSocket.opened.length).toBe(2);
  });

  it('divides the width going right, and the height going down', async () => {
    const c = container('hermes-prod');
    const right = render(storeStub([c], c));
    await openPanel(right.fixture);
    splitBtn(right.fixture).click();
    await settle(right.fixture, 300);

    // two groups sharing the width — this is the split that halves the columns
    expect(gridTop()).toEqual({ type: 'leaf+leaf', kids: 2 });
    right.fixture.destroy();
    localStorage.clear();

    const down = render(storeStub([c], c));
    await openPanel(down.fixture);
    stackBtn(down.fixture).click();
    await settle(down.fixture, 300);

    // one full-width branch, divided inside — every column survives
    expect(gridTop()).toEqual({ type: 'branch', kids: 1 });
  });

  it('grows the panel before stacking, since half its height is a few rows', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    const before = el(fixture).querySelector<HTMLElement>('.body')!.style.height;
    expect(before).toBe('280px');

    stackBtn(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLElement>('.body')!.style.height).toBe('420px');
  });

  it('leaves the height alone when the split was refused at the cap', async () => {
    const c = container('hermes-prod');
    const { fixture, store } = render(storeStub([c], c));
    await openPanel(fixture);
    const add = el(fixture).querySelector<HTMLButtonElement>('.add')!;
    for (let i = 0; i < 11; i++) add.click();
    await settle(fixture);

    stackBtn(fixture).click();
    await settle(fixture);

    // nothing was added, so nothing needs the room
    expect(store.ctx.toast).toHaveBeenCalledWith(
      'terminal tab limit (12) reached — close a tab first');
    expect(el(fixture).querySelector<HTMLElement>('.body')!.style.height).toBe('280px');
    expect(localStorage.getItem('mc-terminal-height')).toBeNull();
  });

  it('leaves a panel the operator already made taller alone', async () => {
    localStorage.setItem('mc-terminal-height', '600');
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    stackBtn(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLElement>('.body')!.style.height).toBe('600px');
  });

  it('splitting right does not touch the height — the columns are the cost there', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    splitBtn(fixture).click();
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLElement>('.body')!.style.height).toBe('280px');
  });
});

/**
 * The reason ⬓ and the column floor both exist: a pane that has printed output must
 * not be narrowed, because xterm rewraps what it already holds and hermes never
 * redraws its banner. The floor's own rules are terminal-session.spec.ts' subject;
 * what this pins is that the panel no longer offers a control that would break it.
 */
describe('TerminalPanel column floor', () => {
  liveShells();

  it('offers no wrap toggle — the floor is not a thing to switch off', () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));

    expect(el(fixture).querySelector('button[title*="wrap"]')).toBeNull();
  });

  it('sends no narrowing resize for a pane that has already printed', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    const socket = FakeSocket.opened[0];
    socket.onopen?.();
    socket.onmessage?.({ data: '── a rule drawn at the full width ──' });
    await settle(fixture);

    const before = socket.sent
      .filter(f => f.startsWith('{"type":"resize"'))
      .map(f => (JSON.parse(f) as { cols: number }).cols);

    // the shell was told a grid, printed at it, and is now sharing the panel
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!.click();
    await settle(fixture, 300);

    const after = socket.sent
      .filter(f => f.startsWith('{"type":"resize"'))
      .map(f => (JSON.parse(f) as { cols: number }).cols);
    const floor = Math.max(...before, 0);
    expect(after.every(cols => cols >= floor)).toBe(true);
  });

  it('still lets ⌫ put a pane back to fitting its box', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    // the button exists and clearing a pane is what drops its floor; the drop itself
    // is asserted in terminal-session.spec.ts, against a box that can be measured
    el(fixture).querySelector<HTMLButtonElement>('button[title="clear"]')!.click();
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.tab').length).toBe(1);
  });
});

describe('TerminalPanel keyboard', () => {
  liveShells();

  const chord = (fixture: Fixture, key: string): void => {
    const target = document.activeElement ?? el(fixture);
    target.dispatchEvent(new KeyboardEvent('keydown', {
      key, ctrlKey: true, shiftKey: true, bubbles: true, cancelable: true,
    }));
  };

  it('says what a tab is, so the strip is not divs to a screen reader', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    const tab = tabs(fixture)[0];
    expect(tab.getAttribute('role')).toBe('tab');
    expect(tab.getAttribute('aria-selected')).toBe('true');
    // roving tabindex: the strip is one Tab stop, not one per open shell
    expect(tab.tabIndex).toBe(0);
    expect(tab.closest('[role="tablist"]')).not.toBeNull();
  });

  it('takes the inactive tabs out of the tab order', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    const order = tabs(fixture).map(t => t.tabIndex);
    expect(order.filter(i => i === 0).length).toBe(1);
    expect(tabs(fixture).map(t => t.getAttribute('aria-selected')))
      .toEqual(['false', 'true']);
  });

  it('moves the keyboard between panes without a pointer', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!.click();
    await settle(fixture, 300);

    const before = tabs(fixture).findIndex(t => t.classList.contains('act'));
    chord(fixture, 'ArrowLeft');
    await settle(fixture);

    const after = tabs(fixture).findIndex(t => t.classList.contains('act'));
    expect(after).not.toBe(before);

    chord(fixture, 'ArrowRight');
    await settle(fixture);
    expect(tabs(fixture).findIndex(t => t.classList.contains('act'))).toBe(before);
  });

  it('activates a pane from its tab with Enter, which role=tab promises', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('.add')!.click();
    await settle(fixture);

    tabs(fixture)[0].dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
    await settle(fixture);

    expect(tabs(fixture)[0].classList).toContain('act');
  });
});

describe('TerminalPanel over-wide notice', () => {
  liveShells();

  const notice = (fixture: Fixture): HTMLElement[] =>
    Array.from(el(fixture).querySelectorAll<HTMLElement>('.mc-term-notice'));

  it('gives every pane a notice, shown only while the floor is holding it wide', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    expect(notice(fixture).length).toBe(1);
    expect(notice(fixture)[0].classList).not.toContain('on');
  });

  it('never writes the notice into the shell, which is the scrollback people copy', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    const socket = FakeSocket.opened[0];
    socket.onopen?.();
    socket.onmessage?.({ data: 'output drawn at the full width' });
    await settle(fixture);

    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!.click();
    await settle(fixture, 300);

    // whatever the floor does, it does not do it by typing into the terminal
    expect(el(fixture).textContent).not.toContain('cols in a');
    expect(notice(fixture)[0].textContent).not.toContain('\u001b');
  });

  it('offers a refit that clears, and says so rather than surprising anyone', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    const refit = notice(fixture)[0].querySelector<HTMLButtonElement>('.refit')!;
    expect(refit.title).toContain('clear');
    refit.click();
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.tab').length).toBe(1);   // the pane survived it
  });
});

describe('TerminalPanel arrangement round-trip', () => {
  liveShells();

  /**
   * The guard on the one contract dockview does not publish: the shape of its serialized
   * layout, which pruneLayout walks by hand. A version that renames or restructures a leaf
   * would still round-trip through dockview itself, so only putting a real toJSON() back
   * through the prune and into a fresh dock catches it — the failure mode otherwise is a
   * silent fall back to one group, which no other test would notice.
   */
  it('restores the splits a previous visit was left in', async () => {
    const c = container('hermes-prod');
    const first = render(storeStub([c], c));
    await openPanel(first.fixture);
    el(first.fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!.click();
    await settle(first.fixture, 300);
    expect(groups(first.fixture)).toBe(2);

    const saved = localStorage.getItem('mc-terminal-tabs');
    expect(JSON.parse(saved!).layout).toBeTruthy();
    first.fixture.destroy();
    localStorage.setItem('mc-terminal-tabs', saved!);

    const second = render(storeStub([c], c));
    await openPanel(second.fixture);

    // side by side again, not stacked — which is the fallback a broken shape would give
    expect(groups(second.fixture)).toBe(2);
    expect(sashes(second.fixture)).toBe(1);
    expect(tabs(second.fixture).length).toBe(2);
  });

  it('holds every pane above the size a terminal stops being readable at', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    el(fixture).querySelector<HTMLButtonElement>('button[title^="split right"]')!.click();
    await settle(fixture, 300);

    // reached through the private fields on purpose: enforcing the floor is dockview's job,
    // so the only thing worth pinning is that every group was actually told about it —
    // including one that arrived from a split rather than with the dock
    // the readable minimums sit on the group panel, not on its api — setConstraints() pushes
    // them down into the grid and the panel is what reports back what it settled on
    const panes = (fixture.componentInstance as unknown as {
      dock: { api: { groups: { minimumWidth: number; minimumHeight: number }[] } };
    }).dock.api.groups;
    expect(panes.length).toBe(2);
    expect(panes.map(g => g.minimumWidth)).toEqual([220, 220]);
    expect(panes.map(g => g.minimumHeight)).toEqual([80, 80]);
  });
});

describe('TerminalPanel command rail', () => {
  liveShells();

  const cmds = (fixture: Fixture): HTMLElement =>
    el(fixture).querySelector<HTMLElement>('button[title="hermes commands"]')!;

  const firstRow = (fixture: Fixture): HTMLElement =>
    el(fixture).querySelector<HTMLElement>('.cheat .cmd')!;

  it('opens beside the shells rather than above them', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);

    cmds(fixture).click();
    await settle(fixture);

    // inside the body, next to the shells — not a band between the bar and them,
    // which is what used to take height away from the output
    const body = el(fixture).querySelector('.body')!;
    expect(body.querySelector('.cheat')).not.toBeNull();
    expect(body.querySelector('.shells')).not.toBeNull();
  });

  it('folds each description away, and opens it on request', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    cmds(fixture).click();
    await settle(fixture);

    expect(firstRow(fixture).querySelector('.about')).toBeNull();

    const disc = firstRow(fixture).querySelector<HTMLButtonElement>('.act.disc')!;
    expect(disc.getAttribute('aria-expanded')).toBe('false');
    disc.click();
    await settle(fixture);

    expect(firstRow(fixture).querySelector('.about')).not.toBeNull();
    expect(firstRow(fixture).querySelector('.act.disc')!.getAttribute('aria-expanded'))
      .toBe('true');
  });

  it('opens more than one at a time — comparing two is why you open one', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    cmds(fixture).click();
    await settle(fixture);

    const discs = () => Array.from(
      el(fixture).querySelectorAll<HTMLButtonElement>('.cheat .cmd .act.disc'));
    discs()[0].click();
    await settle(fixture);
    discs()[1].click();
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.cheat .cmd .about').length).toBe(2);
  });

  it('labels its glyph actions, since the words are gone', async () => {
    const c = container('hermes-prod');
    const { fixture } = render(storeStub([c], c));
    await openPanel(fixture);
    cmds(fixture).click();
    await settle(fixture);

    const row = firstRow(fixture);
    expect(row.querySelector('[aria-label="insert"]')).not.toBeNull();
    expect(row.querySelector('[aria-label="copy"]')).not.toBeNull();
    expect(row.querySelector('[aria-label="docs"]')).not.toBeNull();
    // and no worded button survived into the rail
    expect(row.textContent).not.toContain('insert');
  });
});
