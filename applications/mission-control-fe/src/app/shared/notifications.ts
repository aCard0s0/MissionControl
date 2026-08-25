import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked,
} from '@angular/core';
import { ActivityStore } from '../core/store/activity-store';
import { StoreContext } from '../core/store/store-context';
import { elapsed } from '../core/format';
import { reducedMotion } from '../core/motion';

/** One card on the stack: work still running, or how a piece of it ended. */
export interface Note {
  /** Stable across a re-render, and distinct between the two sources. */
  readonly key: string;
  readonly kind: 'running' | 'ok' | 'error';
  readonly message: string;
  /** When the work started, for the elapsed readout. Running cards only. */
  readonly at: number;
  /** Toast id, for the dismiss control. Absent while the work is still running. */
  readonly toastId?: number;
  /** Its source has dropped it; it is on screen only until the exit animation ends. */
  readonly leaving?: boolean;
}

/** Long enough to read as a movement, short enough not to delay the next card. */
const EXIT_MS = 200;

/**
 * The notification stack: what is running now, and how the last few things ended.
 *
 * <p>It sits at the top right rather than above the page content, because it
 * outlives the page. A deploy started on Blueprints is still running when the
 * operator is three pages away, and a banner in the router outlet would either
 * travel with them or push every page down by a row for minutes at a time.
 *
 * <p>Two sources, one column: {@link ActivityStore} for work in flight, and the
 * toast queue for how it ended. They stay separate stores because an ending is
 * not always an operation's — a probe fails on its own — but they read as one
 * conversation, so they share the column and its ordering.
 */
@Component({
  selector: 'mc-notifications',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './notifications.html',
  styleUrl: './notifications.scss',
})
export class Notifications {
  private readonly activity = inject(ActivityStore);
  private readonly ctx = inject(StoreContext);

  /** Ticks the elapsed readout; a counter frozen at 0s reads as a stalled deploy. */
  private readonly now = signal(Date.now());

  /**
   * The column as it is on screen, including cards already dropped by their
   * source and still animating out.
   *
   * <p>Held as state rather than derived from the two sources each time, because
   * position is the thing being preserved: a card keeps the slot it was added
   * in, so one leaving does not shuffle the rest as it fades. Deriving it would
   * mean sorting on a timestamp, and two sources stamping the same millisecond —
   * a deploy that fails instantly does exactly that — have no order at all.
   */
  protected readonly notes = signal<readonly Note[]>([]);

  /** What the two sources hold right now. */
  private readonly live = computed<Note[]>(() => [
    ...this.activity.active().map(a => ({
      key: `run-${a.id}`, kind: 'running' as const, message: a.label, at: a.startedAt,
    })),
    ...this.ctx.toasts().map(t => ({
      key: `toast-${t.id}`, kind: t.kind, message: t.message, at: t.at, toastId: t.id,
    })),
  ]);

  constructor() {
    const clock = setInterval(() => this.now.set(Date.now()), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(clock));

    // Angular drops a @for row the moment its source does, so a card would vanish on a frame
    // boundary. This is the single writer of the column: it appends what arrived, marks what
    // left, and removes the marked ones once their animation has had time to run.
    effect(() => {
      const current = this.live();
      untracked(() => this.reconcile(current));
    });
  }

  private reconcile(current: Note[]): void {
    const held = new Set(current.map(n => n.key));
    const previous = this.notes();
    const dropped = previous.filter(n => !n.leaving && !held.has(n.key)).map(n => n.key);
    const kept = previous.map(n => dropped.includes(n.key) ? { ...n, leaving: true } : n);
    const arrived = current.filter(n => !previous.some(p => p.key === n.key));
    if (!dropped.length && !arrived.length) return;

    this.notes.set([...kept, ...arrived]);
    if (!dropped.length) return;

    const release = () => this.notes.update(list => list.filter(n => !dropped.includes(n.key)));
    // an operator who asked the OS for less motion gets the removal, not a wait before it
    if (reducedMotion()) release();
    else setTimeout(release, EXIT_MS);
  }

  /** How long this operation has been running, recomputed on the stack's own tick. */
  protected since(at: number): string {
    this.now();
    return elapsed(at);
  }

  protected dismiss(note: Note): void {
    if (note.toastId !== undefined) this.ctx.dismiss(note.toastId);
  }
}
