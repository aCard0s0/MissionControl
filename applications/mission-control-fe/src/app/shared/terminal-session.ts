import { signal } from '@angular/core';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';

/** Where a tab's shell connects — a container on a docker host. */
export interface TermTarget {
  hostId: string;
  containerId: string;
  /** container name snapshot; shown as the tab label when the container is gone */
  label: string;
  /** the agent (AgentProfile.id) this tab was opened for. Set only by the
   *  "open shell for agent" shortcut, which uses it to focus the tab it already
   *  made instead of stacking a new one per click. */
  agentKey?: string;
  /** typed into the shell once it is live, on every (re)connect — a reconnect
   *  is always a brand-new exec, so re-running it is the correct behaviour. */
  command?: string;
}

/** Persisted shape (localStorage) — only the reconnect target, never the live
 *  socket or scrollback. The exec session always restarts on reconnect. */
export interface PersistedTab {
  id: string;
  hostId: string;
  containerId: string;
  label: string;
  agentKey?: string;
  command?: string;
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

  /**
   * The size the backend PTY was last told about.
   *
   * <p>A height drag resizes the host div on every pointer frame, and most of those pixel
   * steps land on the same character grid — so an unguarded fit sent dozens of identical
   * `resize` frames per drag. Each one is a SIGWINCH the shell answers by redrawing its
   * prompt, which is what stamped `qa › qa › qa ›` across the input line.
   */
  private lastCols = 0;
  private lastRows = 0;
  private fitQueued = false;

  /**
   * Set while the panel is being dragged.
   *
   * <p>Fitting reflows xterm's buffer, and the far end repaints its input line on every
   * SIGWINCH that follows. Doing that per pointer frame is what left the prompt drawn twice:
   * the app repaints from where it thinks the cursor is, and a buffer that reflowed underneath
   * it no longer agrees. The panel suspends fits for the drag and fits once when it settles,
   * so a drag costs one reflow and one SIGWINCH instead of one per frame.
   */
  private fitsSuspended = false;
  private readonly encoder = new TextEncoder();
  /** has connect() ever run — re-parking the host div must not restart a shell */
  private started = false;
  /** only the on-screen tab fits + reports its size to the backend */
  private active = false;
  /** target().command awaiting a live shell; nulled the moment it is sent */
  private pending: string | null = null;
  /** whether {@link pending} ends in a newline — false for an inserted line */
  private pendingSubmit = true;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

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
    this.observer = new ResizeObserver(() => this.queueFit());
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
    const { hostId, containerId, label, command } = this.target();
    if (!containerId) return;
    this.started = true;
    // a new socket has been told nothing, so the next fit must report even an unchanged grid
    this.lastCols = 0;
    this.lastRows = 0;
    this.ws?.close(1000);
    this.status.set('connecting');
    this.armCommand(command);

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
      // safety net for a shell that prints no prompt — see flushCommand()
      if (this.pending) {
        this.pendingTimer = setTimeout(() => this.flushCommand(ws), 1500);
      }
    };
    ws.onmessage = e => {
      if (this.ws !== ws) return;   // drop frames from a superseded socket
      if (typeof e.data === 'string') this.term?.write(e.data);
      else this.term?.write(new Uint8Array(e.data as ArrayBuffer));
      this.flushCommand(ws);
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;   // superseded by a newer socket
      this.clearPending();
      this.status.set('closed');
      this.term?.write('\r\n\x1b[2m[session closed — ↻ to reconnect]\x1b[0m\r\n');
    };
  }

  /**
   * Put `text` at the prompt WITHOUT running it — no trailing newline, so the operator is
   * still the one who presses Enter. That is the whole safety story of the command drawer:
   * the list it comes from contains `uninstall` and `config set`, and a click is too cheap
   * to be a decision to run one.
   *
   * A tab whose shell is not up yet arms it the same way a startup command is armed, so
   * inserting into a reconnecting tab lands once the exec is actually wired rather than
   * disappearing into a socket the backend is still ignoring.
   */
  type(text: string): void {
    if (!text) return;
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(this.encoder.encode(text));
      this.term?.focus();
      return;
    }
    this.armCommand(text, false);
  }

  /**
   * Arms the text {@link flushCommand} sends once the shell is live. `submit` is what
   * separates a startup command (runs itself) from an inserted line (waits for Enter).
   *
   * A tab with no startup command arms nothing and, crucially, discards nothing: connect()
   * calls this on every (re)connect, and a line inserted into a tab whose shell is still
   * coming up is armed here — clearing on an absent command would drop exactly that.
   */
  private armCommand(command: string | undefined, submit = true): void {
    if (this.pendingTimer !== null) {
      clearTimeout(this.pendingTimer);
      this.pendingTimer = null;
    }
    if (!command) return;
    this.pending = command;
    this.pendingSubmit = submit;
  }

  /**
   * Type the tab's startup command into the shell, once.
   *
   * It cannot go out on `onopen`: the WebSocket handshake completes before the
   * backend has created the docker exec, and `TerminalSocketHandler` silently
   * drops stdin frames while its `Shell` is still unregistered — the command
   * would simply vanish. The first output frame is proof the exec is wired, so
   * that is the trigger; the `onopen` timer only covers a shell that emits no
   * prompt at all.
   */
  private flushCommand(ws: WebSocket): void {
    if (this.ws !== ws || !this.pending || ws.readyState !== WebSocket.OPEN) return;
    const cmd = this.pending;
    const submit = this.pendingSubmit;
    this.clearPending();
    ws.send(this.encoder.encode(submit ? cmd + '\n' : cmd));
  }

  private clearPending(): void {
    this.pending = null;
    this.pendingSubmit = true;
    if (this.pendingTimer !== null) {
      clearTimeout(this.pendingTimer);
      this.pendingTimer = null;
    }
  }

  /** Close the live socket but keep the Terminal + scrollback, so the backend
   *  releases this exec immediately (e.g. the container vanished, or the page is
   *  unloading). The onclose handler marks the tab closed; ↻ revives it. */
  closeSocket(): void {
    this.clearPending();
    this.ws?.close(1000);
  }

  /** Point this tab at a different container and reconnect (same Terminal). */
  repoint(target: TermTarget): void {
    this.ws?.close(1000);
    this.target.set(target);
    this.term?.write(`\r\n\x1b[2m── switching to ${target.label} ──\x1b[0m\r\n`);
    this.connect();
  }

  /**
   * Collapses a burst of layout changes into one fit.
   *
   * <p>Not for drags — those are excluded outright by {@link fitsSuspended} on the next line.
   * This covers the bursts nothing suspends: a window resize, the panel opening, the command
   * drawer toggling. The observer can fire several times as one of those settles, and each
   * fit measures and reflows the whole buffer.
   */
  private queueFit(): void {
    if (this.fitsSuspended || this.fitQueued) return;
    this.fitQueued = true;
    requestAnimationFrame(() => {
      this.fitQueued = false;
      this.fitNow();
    });
  }

  /** Fit + report size — only for the on-screen tab. Background tabs fit when
   *  they next become active, so a height drag does not fan resize frames out
   *  across every open socket. */
  fitNow(): void {
    if (!this.term || !this.active) return;

    // A fit re-lays the buffer out and leaves the viewport at the bottom. Someone who had
    // scrolled up to read history sees that as the history disappearing on resize, so the
    // distance from the bottom is measured before and restored after. Measured from the
    // bottom rather than as an absolute line, because a reflow moves every absolute line.
    const before = this.term.buffer.active;
    const fromBottom = Math.max(0, before.baseY - before.viewportY);

    try { this.fit.fit(); } catch { /* host not measurable yet */ }

    if (fromBottom > 0) {
      const after = this.term.buffer.active;
      this.term.scrollToLine(Math.max(0, after.baseY - fromBottom));
    }

    const { cols, rows } = this.term;
    // the grid is what the PTY cares about; a height change that does not cross a row
    // boundary is nothing for it to learn, and telling it anyway costs a prompt redraw
    if (cols === this.lastCols && rows === this.lastRows) return;
    this.lastCols = cols;
    this.lastRows = rows;
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'resize', cols, rows }));
    }
  }

  /** See {@link fitsSuspended}. The panel clears this and fits once when the drag settles. */
  setFitsSuspended(suspended: boolean): void {
    this.fitsSuspended = suspended;
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
    this.clearPending();
    this.ws?.close(1000);
    this.observer?.disconnect();
    this.term?.dispose();
    this.hostEl.remove();
  }

  toJSON(): PersistedTab {
    const { hostId, containerId, label, agentKey, command } = this.target();
    return { id: this.id, hostId, containerId, label, agentKey, command };
  }
}
