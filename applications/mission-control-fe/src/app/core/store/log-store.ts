import { computed, inject, Injectable, signal } from '@angular/core';
import { errorMessage } from '../errors';
import { LogEntry } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { toLogEntry } from './wire-mappers';

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

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);

  constructor() {
    // a fresh selection has its own loading/error story, and its tail is fetched
    // immediately instead of waiting out the 5s poll
    this.containers.onSelect(() => {
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
      const lines = await this.ctx.api.containers.logs(c.hostId, c.id, 100);
      this.byContainer.update(m => ({
        ...m,
        [c.id]: lines.map(l => toLogEntry(l, null)),
      }));
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
