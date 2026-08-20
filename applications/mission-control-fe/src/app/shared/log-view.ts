import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
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

  protected readonly visible = computed(() => {
    const level = this.level();
    return level === 'all' ? this.lines() : this.lines().filter(l => l.level === level);
  });

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
