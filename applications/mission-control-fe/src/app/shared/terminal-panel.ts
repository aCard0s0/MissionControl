import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, computed, effect, inject, signal,
  untracked, viewChild,
} from '@angular/core';
import { HermesStore, TerminalRequest } from '../core/hermes-store';
import { HermesContainer } from '../core/models';
import { PanelHeight } from './panel-height';
import { StatusDot } from './status-dot';
import { TermTarget, TerminalSession } from './terminal-session';
import { readTerminalTabs, writeTerminalTabs } from './terminal-tabs';

// UI sanity guard against runaway tab creation; the backend enforces the real
// per-client/global ceiling (mc.terminal.*) and rejects connections past it.
const MAX_TABS = 12;

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
 * streaming into their buffer while another tab is on screen.
 *
 * Which tabs to restore is {@link readTerminalTabs}' business and how tall the
 * panel is {@link PanelHeight}'s; what is left here is the multiplexing itself.
 */
@Component({
  selector: 'mc-terminal-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [StatusDot],
  templateUrl: './terminal-panel.html',
  styleUrl: './terminal-panel.scss',
})
export class TerminalPanel {
  protected readonly store = inject(HermesStore);
  protected readonly mock = this.store.config.dataMode === 'mock';
  private readonly apiBase = this.store.config.apiBaseUrl || location.origin;

  protected readonly open = signal(false);
  protected readonly height = new PanelHeight('mc-terminal-height');
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
      writeTerminalTabs(this.sessions().map(s => s.toJSON()), this.activeId());
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

  // ── height ──────────────────────────────────────────────────────────────
  protected shorter(): void {
    this.resize(-80);
  }

  protected taller(): void {
    this.resize(80);
  }

  protected dragStart(down: PointerEvent): void {
    this.height.drag(down, () => this.active()?.fitNow());
  }

  /** xterm only recomputes its grid when told to, so every resize refits. */
  private resize(delta: number): void {
    this.height.bump(delta);
    this.active()?.fitNow();
  }

  private restoreTabs(): void {
    const { tabs, activeId } = readTerminalTabs();
    if (!tabs.length) return;
    this.sessions.set(tabs.map(t => new TerminalSession(
      {
        hostId: t.hostId, containerId: t.containerId, label: t.label ?? '',
        agentKey: t.agentKey, command: t.command,
      }, this.apiBase, t.id)));
    this.activeId.set(activeId);
  }
}
