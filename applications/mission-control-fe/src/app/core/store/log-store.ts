import { computed, inject, Injectable, signal } from '@angular/core';
import { errorMessage } from '../errors';
import { LogEntry } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { toLogEntry } from './wire-mappers';

/** How many lines a first read asks for. */
const TAIL = 100;

/** Ceiling the merged tail is kept to, so a chatty container cannot grow it forever. */
const MAX_LINES = 500;

/**
 * Folds a cursor read into the tail already held.
 *
 * <p>Docker resolves `since` to whole seconds, so the reply repeats whatever shared the
 * cursor's second. Matching on timestamp *and* message is what drops those without also
 * dropping a genuine repeat of the same message a second later — which a message-only
 * check would, and heartbeat lines are exactly that.
 */
const merge = (held: LogEntry[], fetched: LogEntry[]): LogEntry[] => {
  const key = (l: LogEntry) => `${l.ts} ${l.msg}`;
  const seen = new Set(held.map(key));
  const added = fetched.filter(l => !seen.has(key(l)));
  return added.length ? [...held, ...added].slice(-MAX_LINES) : held;
};

/**
 * Docker log tails, cached per container so switching back to one shows its last
 * known output immediately. The loading/error signals describe the *selected*
 * container only — a background poll never repaints another page's state.
 */
@Injectable({ providedIn: 'root' })
export class LogStore {
  private readonly byContainer = signal<Record<string, LogEntry[]>>({});

  readonly loading = signal(false);
  readonly updatedAt = signal<number | null>(null);
  readonly error = signal<string | null>(null);

  readonly selectedLogs = computed(() =>
    (this.byContainer()[this.containers.selectedContainerId()] ?? []).slice()
      .sort((a, b) => b.ts - a.ts));

  private readonly inFlight = new Set<string>();

  /** Newest line timestamp held per container — the cursor the next poll asks from. */
  private readonly cursors = new Map<string, number>();

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);

  constructor() {
    // a fresh selection has its own loading/error story, and its tail is fetched
    // immediately instead of waiting out the 5s poll
    this.containers.onSelect(id => {
      // the cached tail for this container stays, but a re-selection re-reads it whole:
      // holding a cursor across it would silently skip whatever arrived while it was away
      this.cursors.delete(id);
      this.loading.set(false);
      this.updatedAt.set(null);
      this.error.set(null);
      void this.poll();
    });
  }

  refresh(): void {
    void this.poll();
  }

  async poll(): Promise<void> {
    const c = this.containers.selected();
    if (!c || c.status === 'stopped' || this.inFlight.has(c.id)) return;
    this.inFlight.add(c.id);
    if (this.isSelected(c.id)) {
      this.loading.set(true);
      this.error.set(null);
    }
    try {
      // only what arrived since the newest line already held: a five-second poll that asks
      // for the last hundred lines every time re-reads a tail that has barely moved, and
      // almost everything it transfers is a line the store already has
      const cursor = this.cursors.get(c.id);
      const lines = await this.ctx.api.containers.logs(c.hostId, c.id, TAIL, cursor);
      const fetched = lines.map(l => toLogEntry(l, null));
      this.byContainer.update(m => ({
        ...m,
        [c.id]: cursor === undefined ? fetched : merge(m[c.id] ?? [], fetched),
      }));
      const newest = fetched.reduce((max, l) => Math.max(max, l.ts), cursor ?? 0);
      if (newest > 0) this.cursors.set(c.id, newest);
      if (this.isSelected(c.id)) this.updatedAt.set(Date.now());
    } catch (e) {
      const detail = errorMessage(e, 'log refresh failed');
      if (this.isSelected(c.id)) this.error.set(detail);
    } finally {
      this.inFlight.delete(c.id);
      if (this.isSelected(c.id)) this.loading.set(false);
    }
  }

  private isSelected(containerId: string): boolean {
    return this.containers.selectedContainerId() === containerId;
  }
}
