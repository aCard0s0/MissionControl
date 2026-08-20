import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
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

  it('parks its host div hidden until it is the tab on screen', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    expect(session.hostEl.style.visibility).toBe('hidden');

    session.setActive(true);
    expect(session.hostEl.style.visibility).toBe('visible');

    session.setActive(false);
    expect(session.hostEl.style.visibility).toBe('hidden');
  });

  it('reports no size while it is off screen, so a drag fans no resize frames out', () => {
    const session = new TerminalSession(target(), 'http://mc.test');
    session.connect();
    const ws = FakeSocket.last!;

    session.fitNow();

    expect(ws.sent).toEqual([]);
  });
});
