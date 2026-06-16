import { signal } from '@angular/core';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';

/** Where a tab's shell connects — a container on a docker host. */
export interface TermTarget {
  hostId: string;
  containerId: string;
  /** container name snapshot; shown as the tab label when the container is gone */
  label: string;
}

/** Persisted shape (localStorage) — only the reconnect target, never the live
 *  socket or scrollback. The exec session always restarts on reconnect. */
export interface PersistedTab {
  id: string;
  hostId: string;
  containerId: string;
  label: string;
}

export type TermStatus = 'idle' | 'connecting' | 'connected' | 'closed';

let uid = 0;
const newId = (): string => {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  } catch { /* randomUUID needs a secure context */ }
  return `t-${Date.now().toString(36)}-${uid++}`;
};

/**
 * One terminal tab. Owns a single xterm {@link Terminal} + {@link FitAddon} +
 * {@link WebSocket} and a host `<div>` for its entire life. The panel parks the
 * div in a shared mount slot and toggles its visibility — it never moves the
 * Terminal, which xterm binds to one element for life. Background tabs keep
 * their socket open, so output streams into the buffer while another tab shows.
 *
 * The connect/ensureTerm/fit logic is lifted near-verbatim from the original
 * single-terminal panel; the wire protocol is unchanged (binary frames carry
 * raw bytes; a text frame {"type":"resize","cols":..,"rows":..} sets size).
 */
export class TerminalSession {
  readonly id: string;
  readonly target;
  readonly status = signal<TermStatus>('idle');
  /** created once and term.open()'d once; the panel keeps it parked in #mount */
  readonly hostEl: HTMLDivElement;

  private term: Terminal | null = null;
  private readonly fit = new FitAddon();
  private ws: WebSocket | null = null;
  private observer: ResizeObserver | null = null;
  private readonly encoder = new TextEncoder();
  /** has connect() ever run — re-parking the host div must not restart a shell */
  private started = false;
  /** only the on-screen tab fits + reports its size to the backend */
  private active = false;

  constructor(target: TermTarget, private readonly apiBase: string, id?: string) {
    this.id = id ?? newId();
    this.target = signal<TermTarget>(target);
    // styled imperatively: this div is created outside the component template,
    // so emulated view-encapsulation scoping would not reach a CSS class rule.
    this.hostEl = document.createElement('div');
    this.hostEl.className = 'xterm-host';
    const st = this.hostEl.style;
    st.position = 'absolute';
    st.top = '0'; st.left = '0'; st.right = '0'; st.bottom = '0';
    st.visibility = 'hidden';
  }

  /** Build the xterm instance (idempotent). Call only once hostEl is attached
   *  to the DOM so character measurement is correct. */
  ensureTerm(): void {
    if (this.term) return;
    const css = getComputedStyle(document.body);
    const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
    const term = new Terminal({
      cursorBlink: true,
      fontSize: 12,
      fontFamily: v('--font-mono', 'ui-monospace, SFMono-Regular, Menlo, monospace'),
      // dark literals on purpose — the terminal stays dark in both themes
      theme: {
        background: '#0b0e12',
        foreground: '#e6edf3',
        cursor: '#3ff08f',
        selectionBackground: '#3a4150',
      },
      scrollback: 4000,
    });
    term.loadAddon(this.fit);
    term.open(this.hostEl);
    term.onData(data => {
      if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(this.encoder.encode(data));
    });
    this.observer = new ResizeObserver(() => this.fitNow());
    this.observer.observe(this.hostEl);
    this.term = term;
    this.fitNow();
  }

  /** Connect on first attach only — re-parking the host div (e.g. after the
   *  panel is collapsed and reopened) must NOT restart a live or user-closed
   *  shell. reconnect (↻) and repoint() call connect() directly instead. */
  connectOnce(): void {
    if (this.started || !this.target().containerId) return;
    this.connect();
  }

  /** Open (or restart) the shell socket for the current target. */
  connect(): void {
    const { hostId, containerId, label } = this.target();
    if (!containerId) return;
    this.started = true;
    this.ws?.close(1000);
    this.status.set('connecting');

    const url = this.apiBase.replace(/^http/, 'ws')
      + `/ws/terminal?hostId=${encodeURIComponent(hostId)}&containerId=${encodeURIComponent(containerId)}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.status.set('connected');
      this.term?.writeln(`\x1b[2m── ${label} ──\x1b[0m`);
      this.fitNow();
    };
    ws.onmessage = e => {
      if (this.ws !== ws) return;   // drop frames from a superseded socket
      if (typeof e.data === 'string') this.term?.write(e.data);
      else this.term?.write(new Uint8Array(e.data as ArrayBuffer));
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;   // superseded by a newer socket
      this.status.set('closed');
      this.term?.write('\r\n\x1b[2m[session closed — ↻ to reconnect]\x1b[0m\r\n');
    };
  }

  /** Close the live socket but keep the Terminal + scrollback, so the backend
   *  releases this exec immediately (e.g. the container vanished, or the page is
   *  unloading). The onclose handler marks the tab closed; ↻ revives it. */
  closeSocket(): void {
    this.ws?.close(1000);
  }

  /** Point this tab at a different container and reconnect (same Terminal). */
  repoint(target: TermTarget): void {
    this.ws?.close(1000);
    this.target.set(target);
    this.term?.write(`\r\n\x1b[2m── switching to ${target.label} ──\x1b[0m\r\n`);
    this.connect();
  }

  /** Fit + report size — only for the on-screen tab. Background tabs fit when
   *  they next become active, so a height drag does not fan resize frames out
   *  across every open socket. */
  fitNow(): void {
    if (!this.term || !this.active) return;
    try { this.fit.fit(); } catch { /* host not measurable yet */ }
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'resize', cols: this.term.cols, rows: this.term.rows }));
    }
  }

  setActive(active: boolean): void {
    this.active = active;
    this.hostEl.style.visibility = active ? 'visible' : 'hidden';
  }

  clear(): void {
    this.term?.clear();
  }

  focus(): void {
    this.term?.focus();
  }

  dispose(): void {
    this.ws?.close(1000);
    this.observer?.disconnect();
    this.term?.dispose();
    this.hostEl.remove();
  }

  toJSON(): PersistedTab {
    const { hostId, containerId, label } = this.target();
    return { id: this.id, hostId, containerId, label };
  }
}
