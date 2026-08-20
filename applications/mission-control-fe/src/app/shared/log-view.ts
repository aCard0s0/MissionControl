import {
  ChangeDetectionStrategy, Component, ElementRef, computed, effect, input, signal, viewChild,
} from '@angular/core';
import { LogEntry, LogLevel } from '../core/models';
import { logStamp } from '../core/format';
import { PanelHeight } from './panel-height';

type Filter = LogLevel | 'all';

const FILTERS: readonly Filter[] = ['all', 'error', 'warn', 'info', 'debug'];

/**
 * One rendering of a levelled log tail, wherever a tail is shown.
 *
 * <p>The container tail, the agent gateway tail, an MCP server's tail and the dashboard's own
 * were four copies of the same rows, which is how three of them ended up without a date and
 * how the level filter existed on exactly one — and there without `info`, so the level most
 * lines carry could not be isolated.
 *
 * <p>The panel owns its filter and its height; the page above it owns the header, the refresh
 * and the poll, because those differ per source and the rows do not.
 */
@Component({
  selector: 'mc-log-view',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './log-view.html',
  styleUrl: './log-view.scss',
})
export class LogView {
  readonly lines = input.required<LogEntry[]>();
  readonly loading = input(false);
  readonly error = input<string | null>(null);
  readonly emptyText = input('No log entries.');

  /**
   * Where this panel's height is remembered. Distinct per placement so the container tail and
   * the server log page do not fight over one stored value.
   */
  readonly heightKey = input('mc-log-height');

  protected readonly filters = FILTERS;
  protected readonly stamp = logStamp;

  protected readonly level = signal<Filter>('all');

  /** Rebuilt when the key changes, so each placement loads its own remembered height. */
  protected readonly height = computed(() => new PanelHeight(this.heightKey(), 260));

  /** How many lines each level would show, for the counts on the filter buttons. */
  protected readonly counts = computed(() => {
    const tally: Record<string, number> = { all: this.lines().length, error: 0, warn: 0, info: 0, debug: 0 };
    for (const l of this.lines()) tally[l.level] = (tally[l.level] ?? 0) + 1;
    return tally;
  });

  /**
   * Oldest at the top, newest on the last line — the way `tail -f` and every terminal reads.
   *
   * <p>Sorted here rather than reversed, because the four callers disagree: the container and
   * server tails hand these over newest-first so a caller can take the newest N, while the MCP
   * reader already sorts ascending. Ordering by the timestamp is the one rule that is right
   * for all of them.
   */
  protected readonly visible = computed(() => {
    const level = this.level();
    const rows = level === 'all' ? this.lines() : this.lines().filter(l => l.level === level);
    return rows.slice().sort((a, b) => a.ts - b.ts);
  });

  private readonly body = viewChild<ElementRef<HTMLDivElement>>('body');

  /**
   * Whether new lines pull the view down with them.
   *
   * <p>True until the operator scrolls up, because yanking someone back to the bottom while
   * they are reading is the thing that made older lines feel unreachable. Scrolling back to
   * the bottom re-arms it, which is what every log viewer does.
   */
  private follow = true;

  constructor() {
    effect(() => {
      this.visible();
      this.height().px();     // a taller panel reveals more; keep the newest line in view
      if (this.follow) queueMicrotask(() => this.toBottom());
    });
  }

  private toBottom(): void {
    const el = this.body()?.nativeElement;
    if (el) el.scrollTop = el.scrollHeight;
  }

  /** A few pixels of slack: a browser can land fractionally short of the true bottom. */
  protected onScroll(event: Event): void {
    const el = event.target as HTMLElement;
    this.follow = el.scrollHeight - el.scrollTop - el.clientHeight < 8;
  }

  /** The grip sits under the rows, so dragging down is what makes the panel taller. */
  protected grip(down: PointerEvent): void {
    this.height().drag(down, () => { /* nothing to refit — the rows reflow themselves */ }, 'bottom');
  }

  protected key(l: LogEntry): string {
    return `${l.ts}:${l.level}:${l.source}:${l.msg}`;
  }

  /** What the body says when it has nothing to draw, which is three different situations. */
  protected readonly placeholder = computed(() => {
    if (this.loading()) return 'Loading logs…';
    if (this.lines().length && !this.visible().length) return 'No lines at this level.';
    return this.emptyText();
  });
}
