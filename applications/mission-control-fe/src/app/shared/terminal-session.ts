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

/** How long the layout must hold still before a fit is worth its SIGWINCH. See {@link
 *  TerminalSession.fitLater}. Short enough to feel immediate, long enough that a sash drag
 *  or a window resize costs one fit rather than one per frame. */
const FIT_SETTLE_MS = 120;

/** Rows of history a pane keeps. */
const SCROLLBACK_ROWS = 4000;

let uid = 0;
const newId = (): string => {
  try {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  } catch { /* randomUUID needs a secure context */ }
  return `t-${Date.now().toString(36)}-${uid++}`;
};

/**
 * One terminal pane. Owns a single xterm {@link Terminal} + {@link FitAddon} +
 * {@link WebSocket} and a host `<div>` for its entire life. The dock parks that
 * div inside whichever pane currently shows it — it never moves the Terminal,
 * which xterm binds to one element for life. Panes that are not on screen keep
 * their socket open, so output streams into the buffer either way.
 *
 * Nothing here knows about tabs, groups or splits: the session is told when it
 * is on screen ({@link setVisible}) and when its box may have changed
 * ({@link fitLater}), and that is the whole of its relationship with layout.
 *
 * The connect/ensureTerm/fit logic is lifted near-verbatim from the original
 * single-terminal panel; the wire protocol is unchanged (binary frames carry
 * raw bytes; a text frame {"type":"resize","cols":..,"rows":..} sets size).
 */
export class TerminalSession {
  readonly id: string;
  readonly target;
  readonly status = signal<TermStatus>('idle');
  /** created once and term.open()'d once; the dock parks it in a pane element */
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
  private settleTimer: ReturnType<typeof setTimeout> | null = null;

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
  /** only a pane that is actually on screen fits + reports its size to the backend */
  private visible = false;
  /**
   * The widest grid this pane has printed at, and so the narrowest it may be resized to.
   * Zero until output arrives, because an empty screen has nothing to protect.
   *
   * Growing a terminal is harmless. Shrinking one is what does the damage.
   *
   * <p>xterm rewraps hard-wrapped lines when the grid narrows, and output that was printed once
   * and is never redrawn cannot survive that: hermes draws a full-width bordered banner at
   * startup and does not repaint on SIGWINCH, so a rule printed at 236 columns rewrapped at 118
   * puts its right-hand text across the seam and takes the box apart. Every terminal does this —
   * drag any window narrower after running `hermes` to see the same wreckage.
   *
   * <p>So the grid a pane has printed at is a floor it never goes below. A narrower box scrolls
   * sideways to it instead of reflowing, which keeps the output exactly as the shell drew it.
   * The floor is set by what was actually printed rather than by any box this pane once had, and
   * it lifts only when the buffer is empty again — {@link clear} and a reconnect both do that,
   * which is what makes ↻ the way to get a pane back to fitting its box.
   *
   * <p>Deliberately with no expiry. It is tempting to drop the floor once a scrollback's worth
   * of rows has scrolled by, on the grounds that the output it guarded must be gone — but
   * while the floor binds it is holding the grid wide, so everything printed since was drawn
   * wide too. Dropping it then rewraps a buffer that is *entirely* wide content, turning one
   * mangled banner into a whole shredded history. The floor is only ever safe to drop when it
   * is not binding, and then dropping it changes nothing.
   */
  private floorCols = 0;
  /** whether the floor is currently holding the grid wider than the box, so the pane says so
   *  once rather than on every fit */
  private overWide = false;
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
    // deliberately no visibility of its own: which panes are on screen is the
    // dock's layout, and a session that hid itself would fight it.
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
      scrollback: SCROLLBACK_ROWS,
    });
    term.loadAddon(this.fit);
    term.open(this.hostEl);
    term.onData(data => {
      if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(this.encoder.encode(data));
    });
    this.observer = new ResizeObserver(() => this.fitLater());
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
    // a fresh exec redraws from nothing, so whatever the last one printed wide stops mattering
    this.dropFloor();
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
      // the shell has now drawn something at this grid, so this width is a floor
      this.raiseFloor();
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
   * Fit once the layout has stopped moving, rather than on every frame of it moving.
   *
   * <p>Each fit reflows the buffer and sends a SIGWINCH the far end answers by redrawing its
   * prompt, so a burst of them is what stamps `qa › qa › qa ›` across the input line. The
   * height drag avoids that by suspending fits explicitly ({@link setFitsSuspended}), but a
   * dock sash drag has no such bracket — dockview reports a new size per pointer frame and
   * there is no drag-ended event to hang the single fit off. A trailing debounce covers that,
   * and subsumes the bursts a window resize or the command drawer toggling used to produce.
   */
  fitLater(): void {
    if (this.fitsSuspended) return;
    if (this.settleTimer !== null) clearTimeout(this.settleTimer);
    this.settleTimer = setTimeout(() => {
      this.settleTimer = null;
      this.fitNow();
    }, FIT_SETTLE_MS);
  }

  /** Fit + report size — only for a pane that is on screen. A hidden pane fits when it next
   *  becomes visible, so resizing the panel does not fan resize frames out across every open
   *  socket, and a hidden pane's box (which the dock may have collapsed to nothing) is never
   *  mistaken for the size its shell should run at. */
  fitNow(): void {
    if (!this.term || !this.visible) return;

    // A fit re-lays the buffer out and leaves the viewport at the bottom. Someone who had
    // scrolled up to read history sees that as the history disappearing on resize, so the
    // distance from the bottom is measured before and restored after. Measured from the
    // bottom rather than as an absolute line, because a reflow moves every absolute line.
    // A degenerate measurement is worse than no fit at all: resizing to one row pushes the
    // whole screen into scrollback, and one column rewraps every line to nothing. The host is
    // briefly unmeasurable mid-layout — a window resize, the panel opening — so the fit is
    // skipped and the observer that follows the real change performs it.
    //
    // Only the fit is skipped. The size still gets reported below, because a socket that has
    // just opened has been told nothing and needs the grid the terminal already has.
    const proposed = this.fit.proposeDimensions();
    const measurable = !!proposed
        && Number.isFinite(proposed.cols) && Number.isFinite(proposed.rows)
        && proposed.cols >= 2 && proposed.rows >= 2;

    if (measurable && proposed) {
      const before = this.term.buffer.active;
      const fromBottom = Math.max(0, before.baseY - before.viewportY);

      // rows always follow the box — the PTY must not believe it has lines that are not on
      // screen — but columns never go below what this pane has already printed at, and xterm's
      // own viewport scrolls to reach them (`overflow-y: scroll` there resolves `overflow-x`
      // to `auto`). See {@link floorCols}.
      const cols = Math.max(proposed.cols, this.floorCols);
      try { this.term.resize(cols, proposed.rows); } catch { /* host not measurable yet */ }
      this.noteOverWide(cols > proposed.cols, cols, proposed.cols);

      if (fromBottom > 0) {
        const after = this.term.buffer.active;
        this.term.scrollToLine(Math.max(0, after.baseY - fromBottom));
      }
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

  /**
   * Records that the shell has drawn at the grid it currently has, so no later fit may narrow
   * below it. Called for output rather than on resize: a box the pane merely *had* is not
   * something to protect, only a width something was printed at.
   */
  private raiseFloor(): void {
    if (this.term) this.floorCols = Math.max(this.floorCols, this.term.cols);
  }

  /** Lets the pane fit its box again. The buffer is empty, so there is nothing left to wrap. */
  private dropFloor(): void {
    this.floorCols = 0;
    this.overWide = false;
  }

  /**
   * Says once, in the pane, that its grid is wider than its box.
   *
   * <p>Without this the floor is invisible: the output is intact but half of it is off to the
   * right, which reads as truncation rather than as something to scroll or reset. Written as
   * terminal output because that is where the operator is looking, and only on the transition
   * so a drag does not fill the screen with notices.
   */
  private noteOverWide(over: boolean, cols: number, boxCols: number): void {
    if (over === this.overWide) return;
    this.overWide = over;
    if (!over) return;
    this.term?.write(
      `\r\n\x1b[2m── ${cols} cols in a ${boxCols}-col pane; scroll, or ↻ to refit ──`
      + '\x1b[0m\r\n');
  }

  /** See {@link fitsSuspended}. The panel clears this and fits once when the drag settles. */
  setFitsSuspended(suspended: boolean): void {
    this.fitsSuspended = suspended;
    if (suspended && this.settleTimer !== null) {
      clearTimeout(this.settleTimer);   // a fit already queued must not land mid-drag
      this.settleTimer = null;
    }
  }

  /**
   * Whether this pane is on screen. Only that — the dock decides what is shown, and with
   * several panes side by side "visible" is no longer the same question as "focused".
   *
   * <p>A pane that has just come on screen was not fitting while it was hidden, so its shell
   * is still sized to whatever box it last had. The fit is immediate rather than debounced:
   * showing a stale grid for even a frame is a visibly mangled prompt.
   */
  setVisible(visible: boolean): void {
    const changed = this.visible !== visible;
    this.visible = visible;
    if (visible && changed) this.fitNow();
  }

  /** The grid this pane is running, which is not always the grid its box would give it —
   *  see the note on the column floor. */
  grid(): { cols: number; rows: number } {
    return { cols: this.term?.cols ?? 0, rows: this.term?.rows ?? 0 };
  }

  /**
   * Clear the screen, and with it the reason the grid was being held wide.
   *
   * <p>An empty buffer has nothing that could rewrap, so the floor lifts and the pane refits
   * its box — which is what makes ⌫ (and ↻) the way back from a pane scrolling sideways.
   */
  clear(): void {
    this.term?.clear();
    this.dropFloor();
    this.fitNow();
  }

  focus(): void {
    this.term?.focus();
  }

  dispose(): void {
    this.clearPending();
    if (this.settleTimer !== null) clearTimeout(this.settleTimer);
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
