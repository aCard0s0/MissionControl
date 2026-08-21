import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Terminal } from '@xterm/xterm';
import { TerminalSession } from './terminal-session';

/** Minimal stand-in for the browser WebSocket — records what the tab sends. */
class FakeSocket {
  static last: FakeSocket | null = null;
  static readonly OPEN = 1;

  readyState = 1;
  binaryType = '';
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onclose: (() => void) | null = null;
  readonly sent: string[] = [];

  constructor(readonly url: string) {
    FakeSocket.last = this;
  }

  send(data: ArrayBufferView | string): void {
    this.sent.push(typeof data === 'string' ? data : new TextDecoder().decode(data as Uint8Array));
  }

  close(): void {
    this.readyState = 3;
    this.onclose?.();
  }

  /** Pretend the container wrote a shell prompt back. */
  emit(text: string): void {
    this.onmessage?.({ data: text });
  }

  /** The same, as the binary frames a real exec actually sends. */
  emitBytes(text: string): void {
    this.onmessage?.({ data: new TextEncoder().encode(text).buffer });
  }
}

const target = (command?: string) => ({
  hostId: 'dh-local', containerId: 'c-prod', label: 'ops-bot',
  agentKey: 'c-prod--ops-bot', command,
});

describe('TerminalSession startup command', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.last = null;
    vi.stubGlobal('WebSocket', FakeSocket);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('holds the command until the shell answers — the backend drops stdin sent on open', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    expect(ws.sent).toEqual([]);   // exec may not be registered yet

    ws.emit('$ ');
    expect(ws.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('sends the command once, however much output follows', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.emit('$ ');
    ws.emit('more output');
    ws.emit('and more');

    expect(ws.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('falls back to a timer for a shell that prints no prompt', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    vi.advanceTimersByTime(1500);

    expect(ws.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('sends nothing for a tab with no command', () => {
    const s = new TerminalSession(target(), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.emit('$ ');
    vi.advanceTimersByTime(5000);

    expect(ws.sent).toEqual([]);
  });

  it('drops a queued command when the socket closes before the shell replies', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.close();
    vi.advanceTimersByTime(5000);

    expect(ws.sent).toEqual([]);
  });

  it('drops the command when the tab is re-pointed at another container', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();

    s.repoint({ hostId: 'dh-local', containerId: 'c-staging', label: 'staging' });
    const ws = FakeSocket.last!;
    ws.onopen!();
    ws.emit('$ ');
    vi.advanceTimersByTime(5000);

    // `hermes -p ops-bot` must not run in a container without that profile
    expect(ws.sent).toEqual([]);
    expect(s.target().agentKey).toBeUndefined();
  });

  it('re-runs the command on reconnect — every reconnect is a fresh exec', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.connect();
    FakeSocket.last!.onopen!();
    FakeSocket.last!.emit('$ ');

    s.connect();
    const second = FakeSocket.last!;
    second.onopen!();
    second.emit('$ ');

    expect(second.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('round-trips the agent fields through the persisted shape', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test', 't-1');

    expect(s.toJSON()).toEqual({
      id: 't-1', hostId: 'dh-local', containerId: 'c-prod', label: 'ops-bot',
      agentKey: 'c-prod--ops-bot', command: 'hermes -p ops-bot',
    });
  });
});

describe('TerminalSession.type — a line put at the prompt, not run', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.last = null;
    vi.stubGlobal('WebSocket', FakeSocket);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('sends no newline, so the operator is the one who runs it', () => {
    const s = new TerminalSession(target(), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;
    ws.onopen!();

    s.type('hermes -p ops-bot cron list');

    expect(ws.sent).toEqual(['hermes -p ops-bot cron list']);
  });

  it('holds the line for a shell that is still coming up', () => {
    // the drawer can insert into a tab the panel created a tick ago; the backend drops stdin
    // until the exec is registered, so an eager send would vanish
    const s = new TerminalSession(target(), 'http://mc.test');
    s.type('hermes status');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    expect(ws.sent).toEqual([]);

    ws.emit('$ ');
    expect(ws.sent).toEqual(['hermes status']);
  });

  it('is not discarded by the connect of a tab that has no startup command of its own', () => {
    const s = new TerminalSession(target(), 'http://mc.test');
    s.type('hermes doctor');
    s.connect();          // arms nothing — must not clear what is already queued
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.emit('$ ');

    expect(ws.sent).toEqual(['hermes doctor']);
  });

  it('loses to the startup command of an agent tab, which has to run first', () => {
    const s = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    s.type('hermes doctor');
    s.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.emit('$ ');

    expect(ws.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('sends nothing for an empty line', () => {
    const s = new TerminalSession(target(), 'http://mc.test');
    s.connect();
    const ws = FakeSocket.last!;
    ws.onopen!();

    s.type('');

    expect(ws.sent).toEqual([]);
  });
});

describe('TerminalSession identity and wiring', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    FakeSocket.last = null;
    vi.stubGlobal('WebSocket', FakeSocket);
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('takes the id it is restored with, so a saved tab keeps its identity', () => {
    expect(new TerminalSession(target(), 'http://mc.test', 't-saved').id).toBe('t-saved');
  });

  it('mints distinct ids for new tabs', () => {
    const first = new TerminalSession(target(), 'http://mc.test');
    const second = new TerminalSession(target(), 'http://mc.test');

    expect(first.id).not.toBe(second.id);
  });

  it('still mints an id without a secure context to generate a uuid in', () => {
    vi.stubGlobal('crypto', {
      get randomUUID(): never { throw new Error('requires a secure context'); },
    });

    const session = new TerminalSession(target(), 'http://mc.test');

    expect(session.id).toMatch(/^t-/);
  });

  it('speaks ws to an http backend and wss to an https one', () => {
    new TerminalSession(target(), 'http://mc.test').connect();
    expect(FakeSocket.last!.url).toMatch(/^ws:\/\/mc\.test\//);

    new TerminalSession(target(), 'https://mc.test').connect();
    expect(FakeSocket.last!.url).toMatch(/^wss:\/\/mc\.test\//);
  });

  it('escapes the host and container it addresses', () => {
    new TerminalSession(
      { hostId: 'dh/1', containerId: 'c 1', label: 'x' }, 'http://mc.test').connect();

    expect(FakeSocket.last!.url).toContain('hostId=dh%2F1&containerId=c%201');
  });

  it('opens nothing for a tab with no container yet', () => {
    new TerminalSession({ hostId: '', containerId: '', label: '(choose)' }, 'http://mc.test')
      .connect();

    expect(FakeSocket.last).toBeNull();
  });

  it('connects on first attach only, so re-parking the div cannot restart a shell', () => {
    const session = new TerminalSession(target(), 'http://mc.test');

    session.connectOnce();
    const first = FakeSocket.last;
    session.connectOnce();

    expect(FakeSocket.last).toBe(first);
  });

  it('reads the binary frames a real exec sends, not just text ones', () => {
    const session = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    session.connect();
    const ws = FakeSocket.last!;

    ws.onopen!();
    ws.emitBytes('$ ');

    expect(ws.sent).toEqual(['hermes -p ops-bot\n']);
  });

  it('drops frames from a socket that has been superseded', () => {
    const session = new TerminalSession(target('hermes -p ops-bot'), 'http://mc.test');
    session.connect();
    const stale = FakeSocket.last!;
    session.connect();

    stale.onopen!();
    stale.emit('$ ');

    expect(stale.sent).toEqual([]);
  });

  it('releases the exec when the tab is closed or disposed', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.connect();
    const ws = FakeSocket.last!;

    session.closeSocket();
    expect(ws.readyState).toBe(3);
    expect(session.status()).toBe('closed');

    session.dispose();
    expect(session.hostEl.isConnected).toBe(false);
  });

  it('leaves it to the dock to say what is on screen, and never hides itself', () => {
    // The dock parks this div in a pane and shows or hides the pane. A session that
    // also set its own visibility would fight that: with panes side by side, more than
    // one is on screen at once, and none of them is "the tab".
    const session = new TerminalSession(target(), 'http://mc.test');
    expect(session.hostEl.style.visibility).toBe('');

    session.setVisible(true);
    expect(session.hostEl.style.visibility).toBe('');

    session.setVisible(false);
    expect(session.hostEl.style.visibility).toBe('');
  });

  it('reports no size while it is off screen, so a drag fans no resize frames out', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.connect();
    const ws = FakeSocket.last!;

    session.fitNow();

    expect(ws.sent).toEqual([]);
  });
});

/**
 * Resize frames are SIGWINCH at the far end: the shell answers each one by redrawing its
 * prompt. A height drag resizes the host div on every pointer frame, and most of those
 * pixel steps land on the same character grid — so an unguarded fit stamped the prompt
 * across the input line, `qa › qa › qa ›`.
 */
describe('TerminalSession resize reporting', () => {
  beforeEach(() => {
    FakeSocket.last = null;
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.stubGlobal('ResizeObserver', class {
      observe(): void { /* the drag is driven directly below */ }
      disconnect(): void { /* no-op */ }
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

  afterEach(() => vi.unstubAllGlobals());

  /** The size frames sent so far, parsed. */
  const resizes = (ws: FakeSocket) =>
    ws.sent.map(s => { try { return JSON.parse(s); } catch { return null; } })
      .filter((m): m is { type: string; cols: number; rows: number } => m?.type === 'resize');

  it('tells the pty a size once, however many fits land on the same grid', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.ensureTerm();
    session.setVisible(true);
    session.connect();
    const ws = FakeSocket.last!;
    ws.onopen!();

    const afterOpen = resizes(ws).length;
    // what a drag looks like: many fits, one grid
    for (let i = 0; i < 30; i++) session.fitNow();

    expect(resizes(ws).length).toBe(afterOpen);
  });

  /** A ResizeObserver whose callback the test fires by hand, one call per "frame". */
  const observedFrames = (): (() => void) => {
    let fire!: () => void;
    vi.stubGlobal('ResizeObserver', class {
      constructor(cb: () => void) { fire = cb; }
      observe(): void { /* driven by hand */ }
      disconnect(): void { /* no-op */ }
    });
    return () => fire();
  };

  it('collapses a burst of layout changes into one fit, once the layout holds still', () => {
    // A dock sash drag reports a new size per pointer frame and gives no drag-ended event
    // to hang a single fit off. Fitting per frame reflows xterm and the far end repaints
    // its input line on every SIGWINCH that follows — the prompt drawn twice.
    const frame = observedFrames();
    const session = new TerminalSession(target(), 'http://mc.test');
    session.ensureTerm();
    session.setVisible(true);

    // faked only now: xterm wants real timers while it is being built
    const fits = vi.spyOn(session, 'fitNow');
    vi.useFakeTimers();

    for (let i = 0; i < 30; i++) frame();
    expect(fits).not.toHaveBeenCalled();      // nothing lands while it is still moving

    vi.advanceTimersByTime(200);
    expect(fits).toHaveBeenCalledTimes(1);    // thirty frames, one reflow

    vi.useRealTimers();
  });

  it('holds fits for the length of a drag, and drops one already queued', () => {
    const frame = observedFrames();
    const session = new TerminalSession(target(), 'http://mc.test');
    session.ensureTerm();
    session.setVisible(true);

    const fits = vi.spyOn(session, 'fitNow');
    vi.useFakeTimers();

    frame();                                  // a fit is now pending
    session.setFitsSuspended(true);           // the panel's drag begins
    for (let i = 0; i < 30; i++) frame();
    vi.advanceTimersByTime(1000);
    expect(fits).not.toHaveBeenCalled();      // including the one queued before the drag

    // what the panel does when the drag settles
    session.setFitsSuspended(false);
    frame();
    vi.advanceTimersByTime(200);
    expect(fits).toHaveBeenCalledTimes(1);

    vi.useRealTimers();
  });

  it('reports the size again on a new socket, which has been told nothing', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.ensureTerm();
    session.setVisible(true);
    session.connect();
    FakeSocket.last!.onopen!();

    session.connect();                       // ↻ restart
    const fresh = FakeSocket.last!;
    fresh.onopen!();
    session.fitNow();

    expect(resizes(fresh).length).toBeGreaterThan(0);
  });
});

/**
 * Growing a terminal is harmless; shrinking one rewraps every hard-wrapped line it
 * already holds. Output printed once and never redrawn cannot survive that — hermes
 * draws a full-width banner at startup and does not repaint on SIGWINCH — so the grid
 * a pane has printed at becomes a floor it never goes below.
 */
describe('TerminalSession column floor', () => {
  beforeEach(() => {
    FakeSocket.last = null;
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.stubGlobal('ResizeObserver', class {
      observe(): void { /* fits are driven directly below */ }
      disconnect(): void { /* no-op */ }
    });
    vi.stubGlobal('matchMedia', (media: string) => ({
      media, matches: false,
      addListener: () => { /* the dpr never changes here */ },
      removeListener: () => { /* the dpr never changes here */ },
      addEventListener: () => { /* the dpr never changes here */ },
      removeEventListener: () => { /* the dpr never changes here */ },
    }));
  });

  afterEach(() => vi.unstubAllGlobals());

  /**
   * A live pane whose box measures `cols` wide. jsdom lays nothing out, so the
   * measurement the fit addon would take is stubbed — the floor is about which of two
   * numbers wins, and that is decided in the session, not by the browser.
   */
  const pane = (cols: number) => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.ensureTerm();
    session.setVisible(true);
    const box = { cols, rows: 24 };
    (session as unknown as { fit: { proposeDimensions: () => typeof box } })
      .fit.proposeDimensions = () => box;
    session.connect();
    const ws = FakeSocket.last!;
    ws.onopen!();
    // the over-wide notice is terminal output like any other, so it is counted where it
    // is written rather than through an accessor that would exist only for this test
    const term = (session as unknown as { term: Terminal }).term;
    const written: string[] = [];
    const write = term.write.bind(term);
    term.write = (data: string | Uint8Array) => {
      if (typeof data === 'string') written.push(data);
      return write(data);
    };
    return {
      session, ws,
      notices: () => written.filter(w => w.includes('to refit')).length,
      /** resize the box and refit, as a split or a sash drag would */
      resizeTo(next: number): void {
        box.cols = next;
        session.fitNow();
      },
      /** the pty's view of the grid, from the last resize frame on the wire */
      sentCols(): number | undefined {
        const frames = ws.sent
          .filter(f => f.startsWith('{"type":"resize"'))
          .map(f => (JSON.parse(f) as { cols: number }).cols);
        return frames.at(-1);
      },
    };
  };

  it('narrows freely while the screen is still empty', () => {
    const p = pane(200);
    expect(p.session.grid().cols).toBe(200);

    p.resizeTo(80);

    // nothing has been printed, so there is nothing a reflow could damage
    expect(p.session.grid().cols).toBe(80);
    expect(p.sentCols()).toBe(80);
  });

  it('refuses to narrow below what the shell has already printed at', () => {
    const p = pane(200);
    p.ws.emit('── a rule drawn at two hundred columns ──');

    p.resizeTo(80);

    // the pane scrolls sideways to the grid instead of rewrapping it
    expect(p.session.grid().cols).toBe(200);
    expect(p.sentCols()).toBe(200);
  });

  it('still grows, because widening damages nothing', () => {
    const p = pane(120);
    p.ws.emit('printed at a hundred and twenty');

    p.resizeTo(240);

    expect(p.session.grid().cols).toBe(240);
  });

  it('raises the floor again as the shell prints at the wider grid', () => {
    const p = pane(120);
    p.ws.emit('first');
    p.resizeTo(240);
    p.ws.emit('now printed this wide too');

    p.resizeTo(120);

    expect(p.session.grid().cols).toBe(240);
  });

  it('says so in the pane, once, when the floor holds it wider than the box', () => {
    const p = pane(200);
    p.ws.emit('printed wide');

    p.resizeTo(80);
    p.resizeTo(90);
    p.resizeTo(100);

    // the output is intact but half of it is off to the right, which reads as
    // truncation unless the pane says otherwise — and says it exactly once
    expect(p.session.grid().cols).toBe(200);
    expect(p.notices()).toBe(1);
  });

  it('holds the floor however much output goes by, because that output is wide too', () => {
    // Tempting to expire the floor once the guarded banner must have scrolled away. But
    // the floor is what keeps the grid wide, so every row since was drawn wide as well —
    // dropping it would rewrap the whole buffer instead of just the banner.
    const p = pane(200);
    p.ws.emit('printed wide');
    p.resizeTo(80);

    p.ws.emit('line\n'.repeat(9000));
    p.resizeTo(80);

    expect(p.session.grid().cols).toBe(200);
  });

  it('lets go of the floor when the screen is cleared', () => {
    const p = pane(200);
    p.ws.emit('printed wide');
    p.resizeTo(80);
    expect(p.session.grid().cols).toBe(200);

    p.session.clear();

    // an empty buffer holds nothing that could rewrap, so ⌫ is a way back to fitting
    expect(p.session.grid().cols).toBe(80);
  });

  it('lets go of the floor on a reconnect, which redraws from nothing', () => {
    const p = pane(200);
    p.ws.emit('printed wide');
    p.resizeTo(80);

    p.session.connect();          // ↻
    FakeSocket.last!.onopen!();

    expect(p.session.grid().cols).toBe(80);
  });
});
