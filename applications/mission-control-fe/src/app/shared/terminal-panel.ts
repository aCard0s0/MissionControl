import {
  ChangeDetectionStrategy, Component, DestroyRef, ElementRef, effect, inject, signal,
  untracked, viewChild,
} from '@angular/core';
import { Terminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { HermesStore } from '../core/hermes-store';
import { HermesContainer } from '../core/models';

/**
 * VSCode-style bottom terminal panel. Bridges xterm.js to the backend
 * `/ws/terminal` endpoint, which runs `docker exec` (bash/sh, tty) inside the
 * selected container — so `hermes` commands run exactly as they would over
 * `docker exec -it`. Binary frames carry terminal bytes; text frames carry
 * JSON control messages (resize).
 */
@Component({
  selector: 'mc-terminal-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="bar mono" (click)="toggle()">
      <span class="chev">{{ open() ? '▾' : '▴' }}</span>
      <span class="title">TERMINAL</span>
      @if (mock) {
        <span class="faint">— live mode only</span>
      } @else if (store.selectedContainer(); as c) {
        <span class="faint">{{ c.name }}</span>
        <span class="status" [class.on]="status() === 'connected'">{{ status() }}</span>
      } @else {
        <span class="faint">no container selected</span>
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
    @if (open()) {
      <div class="drag" (pointerdown)="dragStart($event)" title="drag to resize"><span class="grip"></span></div>
      <div class="body" [style.height.px]="height()">
        @if (mock) {
          <p class="hint mono">The terminal needs the live backend — switch dataMode to 'live' in config.js.</p>
        } @else if (!store.selectedContainer()) {
          <p class="hint mono">Select a container to open a shell.</p>
        }
        <div #term class="xterm-host" [class.hidden]="mock || !store.selectedContainer()"></div>
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
    .xterm-host { height: 100%; &.hidden { display: none; } }
    .hint { padding: 12px; color: var(--term-dim); }
  `,
})
export class TerminalPanel {
  protected readonly store = inject(HermesStore);
  protected readonly mock = this.store.config.dataMode === 'mock';

  protected readonly open = signal(false);
  protected readonly status = signal<'idle' | 'connecting' | 'connected' | 'closed'>('idle');
  protected readonly height = signal(this.savedHeight());

  private readonly termHost = viewChild<ElementRef<HTMLDivElement>>('term');

  private term: Terminal | null = null;
  private readonly fit = new FitAddon();
  private ws: WebSocket | null = null;
  private connectedTo: string | null = null;
  private observer: ResizeObserver | null = null;
  private readonly encoder = new TextEncoder();

  constructor() {
    // other pages can summon the panel (e.g. "open terminal" on setup hints)
    effect(() => {
      if (this.store.terminalRequest() > 0 && !this.open()) {
        this.open.set(true);
        queueMicrotask(() => this.fitNow());
      }
    });
    // (re)connect when the panel is open and the selected container changes.
    // Keyed on the id — container objects are replaced by every poll.
    effect(() => {
      const el = this.termHost()?.nativeElement;
      this.store.selectedContainerId();
      if (!this.open() || !el || this.mock) return;
      const container = untracked(() => this.store.selectedContainer());
      if (!container) return;
      this.ensureTerm(el);
      if (container.id !== this.connectedTo) this.connect(container);
    });
    inject(DestroyRef).onDestroy(() => {
      this.ws?.close();
      this.observer?.disconnect();
      this.term?.dispose();
    });
  }

  protected toggle(): void {
    this.open.update(v => !v);
    if (this.open()) queueMicrotask(() => this.fitNow());
  }

  protected bump(delta: number): void {
    this.setHeight(this.height() + delta);
    this.fitNow();
  }

  private setHeight(px: number): void {
    const clamped = Math.min(Math.max(px, 120), Math.round(window.innerHeight * 0.7));
    this.height.set(clamped);
    try { localStorage.setItem('mc-terminal-height', String(clamped)); } catch { /* private mode */ }
  }

  private savedHeight(): number {
    try {
      const saved = Number(localStorage.getItem('mc-terminal-height'));
      if (saved >= 120) return saved;
    } catch { /* private mode */ }
    return 280;
  }

  protected clear(): void {
    this.term?.clear();
  }

  protected reconnect(): void {
    this.connectedTo = null;
    const c = this.store.selectedContainer();
    if (c) this.connect(c);
  }

  private ensureTerm(el: HTMLDivElement): void {
    if (this.term) return;
    const css = getComputedStyle(document.body);
    const v = (name: string, fallback: string) => css.getPropertyValue(name).trim() || fallback;
    this.term = new Terminal({
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
    this.term.loadAddon(this.fit);
    this.term.open(el);
    this.term.onData(data => {
      if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(this.encoder.encode(data));
    });
    this.observer = new ResizeObserver(() => this.fitNow());
    this.observer.observe(el);
    this.fitNow();
  }

  private connect(container: HermesContainer): void {
    this.ws?.close(1000);
    this.connectedTo = container.id;
    this.status.set('connecting');

    const base = this.store.config.apiBaseUrl || location.origin;
    const url = base.replace(/^http/, 'ws')
      + `/ws/terminal?hostId=${encodeURIComponent(container.hostId)}&containerId=${encodeURIComponent(container.id)}`;
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    this.ws = ws;

    ws.onopen = () => {
      if (this.ws !== ws) return;
      this.status.set('connected');
      this.term?.writeln(`\x1b[2m── ${container.name} ──\x1b[0m`);
      this.fitNow();
      this.term?.focus();
    };
    ws.onmessage = e => {
      if (typeof e.data === 'string') this.term?.write(e.data);
      else this.term?.write(new Uint8Array(e.data as ArrayBuffer));
    };
    ws.onclose = () => {
      if (this.ws !== ws) return;   // superseded by a newer session
      this.status.set('closed');
      this.connectedTo = null;
      this.term?.write('\r\n\x1b[2m[session closed — ↻ to reconnect]\x1b[0m\r\n');
    };
  }

  private fitNow(): void {
    if (!this.term || !this.open()) return;
    try { this.fit.fit(); } catch { /* host not measurable yet */ }
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'resize', cols: this.term.cols, rows: this.term.rows }));
    }
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
      this.fitNow();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }
}
