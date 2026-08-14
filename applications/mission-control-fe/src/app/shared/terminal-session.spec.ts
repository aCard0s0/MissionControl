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
