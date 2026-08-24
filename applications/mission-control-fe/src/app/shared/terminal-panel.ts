import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, Injector, computed, effect, inject,
  signal, untracked, viewChild,
} from '@angular/core';
import type { SerializedDockview } from 'dockview-core';
import { ContainerStore } from '../core/store/container-store';
import { HostStore } from '../core/store/host-store';
import { StoreContext } from '../core/store/store-context';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { TerminalRequest } from '../core/store/terminal-request-store';
import { HermesContainer } from '../core/models';
import { HermesCommands } from './hermes-commands';
import { PanelHeight } from './panel-height';
import { StatusDot } from './status-dot';
import type { DockHooks, SplitDirection, TerminalDock } from './terminal-dock';
import { TerminalNoticeView } from './terminal-notice-view';
import { TerminalTabView } from './terminal-tab-view';
import { TermTarget, TerminalSession } from './terminal-session';
import { readTerminalTabs, writeTerminalTabs } from './terminal-tabs';

// UI sanity guard against runaway tab creation; the backend enforces the real
// per-client/global ceiling (mc.terminal.*) and rejects connections past it.
const MAX_TABS = 12;

/** A split downward halves the rows, so a panel at its usual height would leave
 *  each shell too short to read. Growing to this first keeps both usable; an
 *  operator who has already made it taller than this keeps their height. */
const STACK_MIN_HEIGHT = 420;

/** How long the layout must hold still before the arrangement is written down.
 *  dockview reports a change per pointer frame of a sash drag, and each write is
 *  a JSON.stringify of the whole grid into synchronous localStorage. */
const SAVE_SETTLE_MS = 250;

/** Where the container picker is drawn, and for which tab. The popover is fixed
 *  to the viewport rather than nested in the tab: dockview's tab strip scrolls
 *  and clips, and a popover that lives inside it would be cut off. */
interface PickerAt {
  id: string;
  x: number;
  y: number;
}

/**
 * VSCode-style bottom terminal panel with TABS AND SPLITS. Each pane is an
 * independent shell — its own xterm.js instance and its own `/ws/terminal`
 * WebSocket to a chosen host+container — and panes can be stacked as tabs in one
 * group or sat side by side, dragged between groups, and resized against each
 * other. The backend already supports N concurrent `docker exec` sessions; this
 * panel multiplexes them and arranges them.
 *
 * The arranging itself is {@link TerminalDock}'s job (a dockview grid), which
 * tabs to restore is {@link readTerminalTabs}', and how tall the whole panel is
 * {@link PanelHeight}'s. What is left here is the panel chrome and the questions
 * only this component can answer: which container a tab is pointed at, what a
 * request from another page should land in, and which pane the toolbar acts on.
 *
 * {@link sessions} is the registry — every live shell, whether or not the dock is
 * currently mounted. The dock is torn down when the panel collapses and rebuilt
 * from that registry plus the saved arrangement when it reopens; a session's
 * socket and scrollback survive both, because nothing about closing the panel
 * ends a shell.
 */
@Component({
  selector: 'mc-terminal-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HermesCommands, StatusDot],
  templateUrl: './terminal-panel.html',
  styleUrl: './terminal-panel.scss',
})
export class TerminalPanel {
  protected readonly containers = inject(ContainerStore);
  protected readonly ctx = inject(StoreContext);
  protected readonly hosts = inject(HostStore);
  protected readonly terminal = inject(TerminalRequestStore);
  /** Bound once: which backend a pane connects to is the api layer's answer, not the
   *  panel's — it only decides which container each pane is pointed at. */
  private readonly socketUrl = (hostId: string, containerId: string): string =>
    this.ctx.api.terminalSocketUrl(hostId, containerId);
  private readonly injector = inject(Injector);

  protected readonly open = signal(false);
  /** the hermes command drawer — a reference you read at the prompt, not away from it */
  protected readonly cheatOpen = signal(false);
  protected readonly height = new PanelHeight('mc-terminal-height');
  protected readonly sessions = signal<TerminalSession[]>([]);
  /** the pane the toolbar acts on — with panes side by side, "on screen" no
   *  longer picks one out, so this is the one that last had the keyboard */
  protected readonly focusedId = signal<string | null>(null);
  protected readonly pickerAt = signal<PickerAt | null>(null);


  protected readonly focused = computed(() =>
    this.sessions().find(s => s.id === this.focusedId()) ?? null);

  /** The tab the open picker belongs to, so it can mark where that tab points now. */
  protected readonly pickerFor = computed(() => {
    const at = this.pickerAt();
    return at ? this.sessions().find(s => s.id === at.id) ?? null : null;
  });

  /** The profile the focused pane is a shell for, so the drawer's lines carry its `-p`. Only an
   *  agent tab has one — a plain container shell is not scoped to any profile. */
  protected readonly focusedProfile = computed(() => {
    const t = this.focused()?.target();
    return t?.agentKey ? t.label : undefined;
  });

  private readonly dockEl = viewChild<ElementRef<HTMLDivElement>>('dock');

  private dock: TerminalDock | null = null;
  /** a mount already in flight — the dock module is fetched on first open */
  private mounting = false;
  /** a tab that wants its picker open as soon as it has a caret to hang it on */
  private pendingPickerId: string | null = null;
  /** pending arrangement capture; see scheduleSave() */
  private saveTimer: ReturnType<typeof setTimeout> | null = null;
  /**
   * The arrangement to rebuild the dock from — kept current as it changes, so a collapse and
   * reopen comes back to the same splits.
   *
   * <p>A signal, so the persistence effect below is the only thing that writes the storage
   * key. It was a plain field, which worked only because every path that changed it also
   * happened to touch a signal the effect read — an invariant held by luck rather than by
   * anything that would fail loudly.
   */
  private readonly arrangement = signal<SerializedDockview | null>(null);

  /** last request seq acted on — a request is handled exactly once */
  private lastSeq = 0;

  constructor() {
    this.restoreTabs();

    // other pages can summon the panel (e.g. "open terminal" on setup hints,
    // or the agent list's shell shortcut)
    effect(() => {
      const req = this.terminal.request();
      if (!req || req.seq === this.lastSeq) return;
      this.lastSeq = req.seq;
      untracked(() => this.handleRequest(req));
    });

    // the dock exists only while the panel is open: its root element is part of
    // the template, so there is nothing to keep it in when the body is gone.
    // Sessions outlive it — mounting hands them straight back their panes.
    effect(() => {
      const host = this.dockEl()?.nativeElement;
      const open = this.open();
      untracked(() => {
        if (!open) return this.unmountDock();
        if (host) void this.mountDock(host);
      });
    });

    // The one writer of the storage key: tab targets and the arrangement, never the live
    // socket or scrollback. Everything else changes a signal and lets this land.
    effect(() => {
      writeTerminalTabs(
        this.sessions().map(s => s.toJSON()), this.focusedId(), this.arrangement());
    });

    // a tab whose container disappeared from the inventory will never stream
    // again — drop its socket so the backend exec is released (don't wait on the
    // server idle reaper). The tab + buffer stay; ↻ revives it if it returns.
    effect(() => {
      const ids = new Set(this.containers.containers().map(c => c.id));
      for (const s of this.sessions()) {
        const cid = s.target().containerId;
        const st = s.status();
        if (cid && !ids.has(cid) && (st === 'connecting' || st === 'connected')) {
          s.closeSocket();
        }
      }
    });

    const destroyRef = inject(DestroyRef);
    // a page holding an open WebSocket is bfcache-ineligible, so pagehide here is
    // a real unload — dispose now so each backend exec frees immediately instead
    // of waiting on the server-side heartbeat/idle reaper.
    const onPageHide = () => this.sessions().forEach(s => s.dispose());
    window.addEventListener('pagehide', onPageHide);
    destroyRef.onDestroy(() => {
      window.removeEventListener('pagehide', onPageHide);
      // the same teardown the panel collapsing uses, so both flush the arrangement — but
      // writing it too, since the effect that would have is already destroyed
      this.unmountDock({ write: true });
      this.sessions().forEach(s => s.dispose());
    });
  }

  protected toggle(): void {
    this.open.update(v => !v);
    if (this.open()) this.onOpened();
  }

  /** Open the panel too — the drawer is only useful with a prompt under it. */
  protected toggleCheat(): void {
    this.cheatOpen.update(v => !v);
    if (this.cheatOpen() && !this.open()) {
      this.open.set(true);
      this.onOpened();
    }
    queueMicrotask(() => this.relayout());
  }

  /**
   * Put a line at the focused prompt without running it.
   *
   * A pane that is still connecting is fine — TerminalSession holds the line for the first
   * live frame. A pane with no container chosen, or one the operator closed, has no shell
   * coming at all: arming a line there would swallow the click silently, so it says so
   * instead and leaves the drawer open, where copy still works.
   */
  protected insertCommand(line: string): void {
    const session = this.focused();
    if (!session || !session.target().containerId || session.status() === 'closed') {
      this.ctx.toast('no live shell — pick a container or reconnect the tab first');
      return;
    }
    session.type(line);
  }

  // ── tabs and panes ──────────────────────────────────────────────────────

  /** A new shell as another tab in the focused pane's group. */
  protected addTab(): void {
    this.newTab(null);
  }

  /**
   * A new shell split away from the focused one.
   *
   * <p>`right` shares the width, which is what halves the columns; `below` shares
   * the height and keeps every column, so wide output stays unwrapped. A
   * downward split grows the panel first, since half of its usual height is only
   * a handful of rows.
   */
  protected splitTab(direction: SplitDirection): void {
    // grown only once there is a second pane to share the height with: the cap is
    // enforced downstream, and a refused split must not leave the panel taller
    if (this.newTab(direction) && direction === 'below'
        && this.height.px() < STACK_MIN_HEIGHT) {
      // the binding reaches the DOM on the next change detection, after the relayout
      // newSession queued — dockview's own observer is what refits to the new height
      this.height.set(STACK_MIN_HEIGHT);
    }
  }

  protected setFocused(id: string): void {
    this.focusedId.set(id);
    this.dock?.focus(id);
  }

  /** Opens the container picker for a tab, under the caret it was clicked from. */
  protected openPicker(session: TerminalSession, anchor: HTMLElement): void {
    const open = this.pickerAt()?.id === session.id;
    if (open) return this.pickerAt.set(null);
    const box = anchor.getBoundingClientRect();
    this.pickerAt.set({ id: session.id, x: box.left, y: box.bottom + 4 });
  }

  protected pickContainer(c: HermesContainer): void {
    const session = this.pickerFor();
    this.pickerAt.set(null);
    if (!session) return;
    session.repoint({ hostId: c.hostId, containerId: c.id, label: c.name });
    this.setFocused(session.id);
  }

  protected reconnect(): void {
    this.focused()?.connect();
  }

  protected clear(): void {
    this.focused()?.clear();
  }

  /** Live container name (absorbs renames), falling back to the saved label.
   *  An agent tab keeps its profile name — that, not the container, is what the
   *  shell is running. */
  protected liveLabel(s: TerminalSession): string {
    const t = s.target();
    if (t.agentKey) return t.label;
    return this.containers.containers().find(c => c.id === t.containerId)?.name ?? t.label;
  }

  /** True when the tab's container no longer exists in the inventory. */
  protected isStale(s: TerminalSession): boolean {
    const id = s.target().containerId;
    return !!id && !this.containers.containers().some(c => c.id === id);
  }

  // ── height ──────────────────────────────────────────────────────────────
  protected shorter(): void {
    this.resize(-80);
  }

  protected taller(): void {
    this.resize(80);
  }

  protected dragStart(down: PointerEvent): void {
    // held for the whole drag so each shell is resized once, at the size it ends on
    this.dock?.suspendFits(true);
    this.height.drag(down, () => {
      this.dock?.suspendFits(false);
      this.relayout();
    });
  }

  /** xterm only recomputes its grid when told to, so every resize refits. */
  private resize(delta: number): void {
    this.height.bump(delta);
    this.relayout();
  }

  /** Hand the dock its new box and refit the shells inside it. */
  private relayout(): void {
    const host = this.dockEl()?.nativeElement;
    if (host) this.dock?.resize(host.clientWidth, host.clientHeight);
    this.dock?.fitAll();
  }

  // ── requests from other pages ───────────────────────────────────────────

  /**
   * Act on a request from another page. An untargeted one keeps the original
   * behaviour (open, seed a tab on the selected container). A targeted one
   * opens a tab pinned to that container — or focuses the tab that target
   * already has, instead of stacking a second shell for it.
   */
  private handleRequest(req: TerminalRequest): void {
    if (!this.open()) this.open.set(true);

    if (!req.containerId) {
      this.onOpened();
      this.insertInto(this.focused(), req.insert);
      return;
    }

    const existing = this.tabFor(req);
    if (existing) {
      this.setFocused(existing.id);
      // the user closed it, or its container went away and came back — revive
      if (existing.status() === 'closed') existing.connect();
      this.insertInto(existing, req.insert);
      return;
    }

    this.insertInto(this.newSession({
      hostId: req.hostId ?? '',
      containerId: req.containerId,
      label: req.label ?? req.containerId,
      agentKey: req.agentKey,
      command: req.command,
    }, null), req.insert);
  }

  /**
   * The tab a repeated request should land in rather than stack another shell onto: an
   * agent's own tab is keyed by its profile, a plain container shell by its container.
   *
   * A container request never adopts an agent tab, however same the container: that
   * prompt is inside `hermes session`, not at the container's own shell.
   */
  private tabFor(req: TerminalRequest): TerminalSession | undefined {
    return this.sessions().find(s => req.agentKey
      ? s.target().agentKey === req.agentKey
      : !s.target().agentKey && s.target().containerId === req.containerId);
  }

  /** Types a requested line into `session`, which may still be connecting — TerminalSession
   *  arms it for the first live frame rather than dropping it into a socket nobody reads. */
  private insertInto(session: TerminalSession | null, insert: string | undefined): void {
    if (session && insert) session.type(insert);
  }

  /** On first open (or external request): seed a tab if empty. The dock connects
   *  every (restored) session as it gives it a pane. */
  private onOpened(): void {
    if (this.sessions().length === 0) this.addTab();
    queueMicrotask(() => this.relayout());
  }

  private newTab(split: SplitDirection | null): TerminalSession | null {
    const c = this.containers.selected();
    const s = this.newSession(c
      ? { hostId: c.hostId, containerId: c.id, label: c.name }
      : { hostId: '', containerId: '', label: '(choose)' }, split);
    // nothing selected — let the operator pick, from the tab the dock just made
    if (s && !c) {
      this.pendingPickerId = s.id;
      queueMicrotask(() => this.openPendingPicker());
    }
    return s;
  }

  /** Append a session and give it a pane, or toast and bail at the cap. */
  private newSession(target: TermTarget, split: SplitDirection | null): TerminalSession | null {
    if (this.sessions().length >= MAX_TABS) {
      this.ctx.toast(`terminal tab limit (${MAX_TABS}) reached — close a tab first`);
      return null;
    }
    const beside = split ? this.focusedId() : null;   // read before the new tab takes focus
    const s = new TerminalSession(target, this.socketUrl);
    this.sessions.update(list => [...list, s]);
    this.focusedId.set(s.id);
    // with the panel closed there is no dock yet; mounting picks the session up
    this.dock?.add(s, beside, split ?? 'right');
    if (split) queueMicrotask(() => this.relayout());   // the panel may have grown
    return s;
  }

  /**
   * Opens the picker for a freshly made unconfigured tab, anchored on its caret.
   *
   * <p>That tab may not exist yet: the first shell of a session is seeded before
   * the dock has been fetched, so there is no strip to hang a popover off. The
   * request is held until there is — {@link mountDock} asks again — rather than
   * drawing the popover in the corner of the viewport.
   */
  private openPendingPicker(): void {
    const id = this.pendingPickerId;
    if (!id) return;
    const caret = this.dockEl()?.nativeElement
      ?.querySelector<HTMLElement>(`.tab[data-session="${id}"] .caret`) ?? null;
    if (!caret) return;
    this.pendingPickerId = null;
    const box = caret.getBoundingClientRect();
    this.pickerAt.set({ id, x: box.left, y: box.bottom + 4 });
  }

  // ── the dock ────────────────────────────────────────────────────────────

  /**
   * Builds the dock, fetching it on first use.
   *
   * <p>The import is dynamic because dockview and its stylesheet are a large
   * chunk and the panel starts collapsed: an operator who never opens a terminal
   * should not pay for the layout engine behind it. Everything the panel itself
   * needs — the bar, the tab count, the saved targets — is here, not there.
   */
  private async mountDock(host: HTMLElement): Promise<void> {
    if (this.dock || this.mounting) return;
    this.mounting = true;
    try {
      const { TerminalDock } = await import('./terminal-dock');
      // the panel may have been collapsed again while the chunk was in flight,
      // which takes the host element with it
      if (!this.open() || this.dock) return;
      const slot = this.dockEl()?.nativeElement ?? host;
      this.dock = new TerminalDock(slot, this.hooks());
      this.dock.restore(this.sessions(), this.arrangement(), this.focusedId());
      this.openPendingPicker();   // a shell seeded before the dock has a strip now
      queueMicrotask(() => this.relayout());
    } finally {
      this.mounting = false;
    }
  }

  /** What the dock asks this panel, and what it tells it. */
  private hooks(): DockHooks {
    return {
      // the tab is built here, not in the dock: what a tab looks like is this
      // component's business, and the dock stays free of Angular for it
      createTab: session => new TerminalTabView(session, {
        label: s => this.liveLabel(s),
        stale: s => this.isStale(s),
        pick: (s, anchor) => this.openPicker(s, anchor),
      }, this.injector),
      // likewise the pane's own chrome: the notice that a pane is being held wider than its
      // box, and the button that clears it
      createNotice: session => new TerminalNoticeView(session, this.injector),
      removed: id => this.dropTab(id),
      focused: id => this.focusedId.set(id),
      arranged: () => this.scheduleSave(),
    };
  }

  /** Capture the arrangement once the layout settles. See {@link SAVE_SETTLE_MS}. */
  private scheduleSave(): void {
    if (this.saveTimer !== null) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout(() => this.saveNow(), SAVE_SETTLE_MS);
  }

  /**
   * Capture the arrangement, now.
   *
   * <p>The one place that does it, because every route to it has to behave the same: the
   * settle timer, the panel collapsing, and the component being torn down. Setting the signal
   * is the whole of it on the first two — the persistence effect writes it. Teardown is the
   * exception: the effect is already gone by then, so that path writes directly.
   */
  private saveNow(): void {
    if (this.saveTimer !== null) {
      clearTimeout(this.saveTimer);
      this.saveTimer = null;
    }
    this.arrangement.set(this.dock?.toJSON() ?? this.arrangement());
  }

  /** The same capture, plus the write the effect would have done. Teardown only. */
  private saveOnTeardown(): void {
    this.saveNow();
    writeTerminalTabs(
      this.sessions().map(s => s.toJSON()), this.focusedId(), this.arrangement());
  }

  private unmountDock(opts: { write?: boolean } = {}): void {
    if (!this.dock) return;
    // captured before the dock stops being able to answer, so a rearrangement made in the
    // last moments before a collapse is not lost with the timer that was going to save it
    if (opts.write) this.saveOnTeardown();
    else this.saveNow();
    this.dock.dispose();
    this.dock = null;
  }

  /** A pane went away: end its shell and take it out of the registry. */
  private dropTab(id: string): void {
    const list = this.sessions();
    const idx = list.findIndex(s => s.id === id);
    if (idx < 0) return;
    list[idx].dispose();
    const rest = list.filter(s => s.id !== id);
    this.sessions.set(rest);
    if (this.pickerAt()?.id === id) this.pickerAt.set(null);
    if (this.pendingPickerId === id) this.pendingPickerId = null;
    if (this.focusedId() === id) {
      this.focusedId.set(rest[Math.min(idx, rest.length - 1)]?.id ?? null);
    }
  }

  private restoreTabs(): void {
    const { tabs, activeId, layout } = readTerminalTabs();
    if (!tabs.length) return;
    this.sessions.set(tabs.map(t => {
      const session = new TerminalSession(
        {
          hostId: t.hostId, containerId: t.containerId, label: t.label ?? '',
          agentKey: t.agentKey, command: t.command,
        }, this.socketUrl, t.id);
      return session;
    }));
    this.focusedId.set(activeId);
    this.arrangement.set(layout);
  }
}
