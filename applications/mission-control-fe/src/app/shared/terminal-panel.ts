import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal,
  untracked, viewChild,
} from '@angular/core';
import { HermesStore, TerminalRequest } from '../core/hermes-store';
import { HermesContainer } from '../core/models';
import { StatusDot } from './status-dot';
import { PersistedTab, TermTarget, TerminalSession } from './terminal-session';

const TABS_KEY = 'mc-terminal-tabs';
const HEIGHT_KEY = 'mc-terminal-height';
// UI sanity guard against runaway tab creation; the backend enforces the real
// per-client/global ceiling (mc.terminal.*) and rejects connections past it.
const MAX_TABS = 12;

/** Versioned envelope for the persisted tab list. */
interface PersistedTabs {
  v: 1;
  tabs: PersistedTab[];
  activeId: string | null;
}

/**
 * VSCode-style bottom terminal panel with TABS. Each tab is an independent
 * shell — its own xterm.js instance and its own `/ws/terminal` WebSocket to a
 * chosen host+container, so two tabs can run different agents in the same
 * container or shells across different containers. The backend already supports
 * N concurrent `docker exec` sessions; this panel just multiplexes them.
 *
 * The live terminals live in a plain {@link TerminalSession} array (invisible
 * to change detection); only lightweight tab chrome is rendered with `@for`.
 * Every session's host div is parked in a single shared #mount slot and shown
 * via `visibility` — so background tabs stay attached, measurable, and keep
 * streaming into their buffer while another tab is on screen. The tab targets
 * persist to localStorage and reconnect (fresh exec) on reload.
 */
@Component({
  selector: 'mc-terminal-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusDot],
  template: `
    <div class="bar mono" (click)="toggle()">
      <span class="chev">{{ open() ? '▾' : '▴' }}</span>
      <span class="title">TERMINAL</span>
      @if (mock) {
        <span class="faint">— live mode only</span>
      } @else if (active(); as a) {
        <span class="faint">{{ liveLabel(a) }}</span>
        <span class="status" [class.on]="a.status() === 'connected'">{{ a.status() }}</span>
      } @else {
        <span class="faint">no shell</span>
      }
      <span class="spacer"></span>
      @if (open()) {
        <button class="act" (click)="bump(-80); $event.stopPropagation()" title="shorter">▼</button>
        <button class="act" (click)="bump(80); $event.stopPropagation()" title="taller">▲</button>
      }
      @if (open() && !mock) {
        <button class="act" (click)="reconnect(); $event.stopPropagation()" title="restart session">↻</button>
        <button class="act" (click)="clear(); $event.stopPropagation()" title="clear">⌫</button>
      }
    </div>

    @if (open() && !mock) {
      <div class="tabs mono">
        @for (s of sessions(); track s.id) {
          <div class="tab" [class.act]="s.id === activeId()" (click)="setActive(s)">
            <mc-status [status]="s.status()" label=" " />
            <span class="lbl" [class.gone]="isStale(s)">{{ liveLabel(s) }}</span>
            <button class="caret" (click)="togglePicker(s); $event.stopPropagation()" title="change container">▾</button>
            <button class="x" (click)="closeTab(s); $event.stopPropagation()" title="close">×</button>
            @if (pickerForId() === s.id) {
              <div class="scrim" (click)="pickerForId.set(null); $event.stopPropagation()"></div>
              <div class="pop">
                @for (c of store.containers(); track c.id) {
                  <button class="row" [class.sel]="c.id === s.target().containerId"
                          (click)="pickContainer(s, c); $event.stopPropagation()">
                    <mc-status [status]="c.status" label=" " />
                    <span class="nm">{{ c.name }}</span>
                    <span class="meta">{{ store.hostById(c.hostId)?.name }}</span>
                  </button>
                } @empty {
                  <div class="none">no containers</div>
                }
              </div>
            }
          </div>
        }
        <button class="add" (click)="addTab(); $event.stopPropagation()" title="new shell">+</button>
      </div>
    }

    @if (open()) {
      <div class="drag" (pointerdown)="dragStart($event)" title="drag to resize"><span class="grip"></span></div>
      <div class="body" [style.height.px]="height()">
        @if (mock) {
          <p class="hint mono">The terminal needs the live backend — switch dataMode to 'live' in config.js.</p>
        } @else if (sessions().length === 0) {
          <p class="hint mono">+ a shell to get started.</p>
        }
        <div #mount class="mount" [class.hidden]="mock || sessions().length === 0"></div>
      </div>
    }
  `,
  styles: `
    // terminal chrome is pinned to the --term-* tokens — dark in both themes
    :host {
      display: flex;
      flex-direction: column;
      border-top: 1px solid var(--term-line);
      background: var(--term-bg);
      color: var(--term-text);
      min-width: 0;
    }
    .bar {
      display: flex;
      align-items: center;
      gap: 10px;
      height: 28px;
      padding: 0 12px;
      font-size: 10px;
      letter-spacing: .18em;
      cursor: pointer;
      user-select: none;
      .chev { color: var(--term-acc); }
      .faint { color: var(--term-faint); letter-spacing: .04em; }
      .status { color: var(--term-faint); letter-spacing: .04em; &.on { color: var(--term-acc); } }
      .spacer { flex: 1; }
      .act {
        background: none; border: 1px solid var(--term-line); color: var(--term-faint);
        border-radius: 3px; cursor: pointer; font-size: 11px; line-height: 1;
        padding: 2px 7px;
        &:hover { color: var(--term-acc); border-color: var(--term-acc); }
      }
    }
    // tab strip — wraps instead of clipping so the picker popover can escape
    .tabs {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px;
      padding: 4px 10px 0;
    }
    .tab {
      position: relative;
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 3px 4px 3px 8px;
      border: 1px solid var(--term-line);
      border-radius: 4px 4px 0 0;
      color: var(--term-faint);
      font-size: 11px;
      white-space: nowrap;
      cursor: pointer;
      &:hover { color: var(--term-text); }
      &.act { color: var(--term-text); border-color: var(--term-acc); background: var(--acc-soft); }
      .lbl { letter-spacing: .02em; &.gone { opacity: .6; text-decoration: line-through; } }
      .caret, .x {
        background: none; border: 0; color: var(--term-faint);
        cursor: pointer; font-size: 11px; line-height: 1; padding: 0 2px;
        &:hover { color: var(--term-acc); }
      }
    }
    .add {
      flex: none;
      background: none; border: 1px solid var(--term-line); color: var(--term-faint);
      border-radius: 4px; cursor: pointer; font-size: 13px; line-height: 1; padding: 1px 8px;
      &:hover { color: var(--term-acc); border-color: var(--term-acc); }
    }
    // container picker — reuses the .ctx-pop look but in dark term tokens
    .scrim { position: fixed; inset: 0; z-index: 80; }
    .pop {
      position: absolute;
      top: calc(100% + 4px);
      left: 0;
      z-index: 90;
      min-width: 220px;
      max-height: 320px;
      overflow-y: auto;
      background: var(--term-bg);
      border: 1px solid var(--term-line);
      border-radius: 6px;
      box-shadow: 0 18px 60px var(--shadow);
      .row {
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        padding: 8px 12px;
        background: none;
        border: 0;
        border-bottom: 1px solid var(--term-line);
        color: var(--term-text);
        font-size: 11px;
        cursor: pointer;
        text-align: left;
        &:hover { background: var(--acc-soft); }
        &.sel { background: var(--acc-soft); color: var(--term-acc); }
        .nm { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
        .meta { margin-left: auto; color: var(--term-faint); font-size: 10px; }
      }
      .none { padding: 10px 12px; color: var(--term-faint); font-size: 11px; }
    }
    .drag {
      height: 8px;
      cursor: row-resize;
      margin: -6px 0 -2px;
      display: flex;
      align-items: center;
      justify-content: center;
      touch-action: none;   /* keep touch drags resizing instead of scrolling */
      .grip {
        width: 44px; height: 3px; border-radius: 2px;
        background: var(--term-line);
      }
      &:hover .grip, &:active .grip { background: var(--term-acc); }
    }
    .body { position: relative; padding: 4px 8px 8px; }
    // host divs are stacked absolutely inside the mount; only the active one is
    // visible (others stay attached + measurable so their buffers keep filling)
    .mount { position: relative; height: 100%; &.hidden { display: none; } }
    .hint { padding: 12px; color: var(--term-dim); }
  `,
})
export class TerminalPanel {
  protected readonly store = inject(HermesStore);
  protected readonly mock = this.store.config.dataMode === 'mock';
  private readonly apiBase = this.store.config.apiBaseUrl || location.origin;

  protected readonly open = signal(false);
  protected readonly height = signal(this.savedHeight());
  protected readonly sessions = signal<TerminalSession[]>([]);
  protected readonly activeId = signal<string | null>(null);
  protected readonly pickerForId = signal<string | null>(null);

  protected readonly active = computed(() =>
    this.sessions().find(s => s.id === this.activeId()) ?? null);

  private readonly mount = viewChild<ElementRef<HTMLDivElement>>('mount');

  /** last request seq acted on — a request is handled exactly once */
  private lastSeq = 0;

  constructor() {
    if (!this.mock) this.restoreTabs();

    // other pages can summon the panel (e.g. "open terminal" on setup hints,
    // or the agent list's shell shortcut)
    effect(() => {
      const req = this.store.terminalRequest();
      if (!req || req.seq === this.lastSeq) return;
      this.lastSeq = req.seq;
      untracked(() => this.handleRequest(req));
    });

    // render loop: park each session's host div in the shared mount slot, build
    // its terminal + connect it once, and show only the active tab. Sessions are
    // removed by closeTab()/dispose(), which detaches their host div directly.
    effect(() => {
      const slot = this.mount()?.nativeElement;
      const list = this.sessions();
      const activeId = this.activeId();
      if (!slot || !this.open() || this.mock) return;
      for (const s of list) {
        if (s.hostEl.parentElement !== slot) {
          slot.appendChild(s.hostEl);   // (re-)park the still-live div in the slot
          s.ensureTerm();
        }
        untracked(() => s.connectOnce());   // first attach only — never on reopen
        s.setActive(s.id === activeId);
      }
      const a = list.find(s => s.id === activeId);
      if (a) queueMicrotask(() => { a.fitNow(); a.focus(); });
    });

    // persist tab targets (not the live socket/scrollback) on any change
    effect(() => {
      if (this.mock) return;
      // only persist configured tabs — an unconfigured "(choose)" tab has no
      // target to restore and restoreTabs() would drop it anyway
      const tabs = this.sessions().map(s => s.toJSON()).filter(t => t.containerId);
      const data: PersistedTabs = { v: 1, tabs, activeId: this.activeId() };
      try { localStorage.setItem(TABS_KEY, JSON.stringify(data)); } catch { /* private mode */ }
    });

    // a tab whose container disappeared from the inventory will never stream
    // again — drop its socket so the backend exec is released (don't wait on the
    // server idle reaper). The tab + buffer stay; ↻ revives it if it returns.
    effect(() => {
      const ids = new Set(this.store.containers().map(c => c.id));
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
      this.sessions().forEach(s => s.dispose());
    });
  }

  protected toggle(): void {
    this.open.update(v => !v);
    if (this.open()) this.onOpened();
  }

  /**
   * Act on a request from another page. An untargeted one keeps the original
   * behaviour (open, seed a tab on the selected container). A targeted one
   * opens a tab pinned to that container — or, when the same agent already has
   * a tab, focuses that one instead of stacking another shell for it.
   */
  private handleRequest(req: TerminalRequest): void {
    if (this.mock) return;
    if (!this.open()) this.open.set(true);

    if (!req.containerId) {
      this.onOpened();
      return;
    }

    const existing = req.agentKey
      ? this.sessions().find(s => s.target().agentKey === req.agentKey)
      : undefined;
    if (existing) {
      // the render effect fits + focuses whatever activeId points at
      this.activeId.set(existing.id);
      // the user closed it, or its container went away and came back — revive
      if (existing.status() === 'closed') existing.connect();
      return;
    }

    this.newSession({
      hostId: req.hostId ?? '',
      containerId: req.containerId,
      label: req.label ?? req.containerId,
      agentKey: req.agentKey,
      command: req.command,
    });
  }

  /** On first open (or external request): seed a tab if empty, then fit. The
   *  render effect attaches + connects every (restored) session. */
  private onOpened(): void {
    if (this.mock) return;
    if (this.sessions().length === 0) this.addTab();
    queueMicrotask(() => this.active()?.fitNow());
  }

  protected addTab(): void {
    const c = this.store.selectedContainer();
    const s = this.newSession(c
      ? { hostId: c.hostId, containerId: c.id, label: c.name }
      : { hostId: '', containerId: '', label: '(choose)' });
    if (s && !c) this.pickerForId.set(s.id);   // nothing selected — let the user pick
  }

  /** Append a tab for `target` and focus it, or toast and bail at the cap. */
  private newSession(target: TermTarget): TerminalSession | null {
    if (this.sessions().length >= MAX_TABS) {
      this.store.toast(`terminal tab limit (${MAX_TABS}) reached — close a tab first`);
      return null;
    }
    const s = new TerminalSession(target, this.apiBase);
    this.sessions.update(list => [...list, s]);
    this.activeId.set(s.id);
    return s;
  }

  protected closeTab(s: TerminalSession): void {
    const idx = this.sessions().indexOf(s);
    s.dispose();
    this.sessions.update(list => list.filter(x => x !== s));
    if (this.pickerForId() === s.id) this.pickerForId.set(null);
    if (this.activeId() === s.id) {
      const rest = this.sessions();
      this.activeId.set(rest[Math.min(idx, rest.length - 1)]?.id ?? null);
    }
  }

  protected setActive(s: TerminalSession): void {
    this.activeId.set(s.id);
  }

  protected togglePicker(s: TerminalSession): void {
    this.pickerForId.update(id => id === s.id ? null : s.id);
  }

  protected pickContainer(s: TerminalSession, c: HermesContainer): void {
    s.repoint({ hostId: c.hostId, containerId: c.id, label: c.name });
    this.pickerForId.set(null);
    this.activeId.set(s.id);
  }

  protected reconnect(): void {
    this.active()?.connect();
  }

  protected clear(): void {
    this.active()?.clear();
  }

  /** Live container name (absorbs renames), falling back to the saved label.
   *  An agent tab keeps its profile name — that, not the container, is what the
   *  shell is running. */
  protected liveLabel(s: TerminalSession): string {
    const t = s.target();
    if (t.agentKey) return t.label;
    return this.store.containers().find(c => c.id === t.containerId)?.name ?? t.label;
  }

  /** True when the tab's container no longer exists in the inventory. */
  protected isStale(s: TerminalSession): boolean {
    const id = s.target().containerId;
    return !!id && !this.store.containers().some(c => c.id === id);
  }

  protected bump(delta: number): void {
    this.setHeight(this.height() + delta);
    this.active()?.fitNow();
  }

  private setHeight(px: number): void {
    const clamped = Math.min(Math.max(px, 120), Math.round(window.innerHeight * 0.7));
    this.height.set(clamped);
    try { localStorage.setItem(HEIGHT_KEY, String(clamped)); } catch { /* private mode */ }
  }

  private savedHeight(): number {
    try {
      const saved = Number(localStorage.getItem(HEIGHT_KEY));
      if (saved >= 120) return saved;
    } catch { /* private mode */ }
    return 280;
  }

  private restoreTabs(): void {
    let raw: string | null = null;
    try { raw = localStorage.getItem(TABS_KEY); } catch { return; }
    if (!raw) return;
    let data: PersistedTabs;
    try { data = JSON.parse(raw); } catch { return; }
    if (data?.v !== 1 || !Array.isArray(data.tabs)) return;
    const list = data.tabs
      .filter(t => t && t.containerId)
      .map(t => new TerminalSession(
        {
          hostId: t.hostId, containerId: t.containerId, label: t.label ?? '',
          agentKey: t.agentKey, command: t.command,
        }, this.apiBase, t.id));
    if (!list.length) return;
    this.sessions.set(list);
    this.activeId.set((list.find(s => s.id === data.activeId) ?? list[0]).id);
  }

  protected dragStart(down: PointerEvent): void {
    down.preventDefault();
    const startY = down.clientY;
    const startH = this.height();
    const move = (e: PointerEvent) => {
      this.setHeight(startH + (startY - e.clientY));
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      this.active()?.fitNow();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }
}
