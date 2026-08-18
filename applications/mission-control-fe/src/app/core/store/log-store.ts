import { computed, signal } from '@angular/core';
import { LogEntry } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/**
 * Docker log tails, cached per container so switching back to one shows its last
 * known output immediately. The loading/error signals describe the *selected*
 * container only — a background poll never repaints another page's state.
 */
export class LogStore {
  private readonly byContainer = signal<Record<string, LogEntry[]>>({});

  readonly loading = signal(false);
  readonly updatedAt = signal<number | null>(null);
  readonly error = signal<string | null>(null);

  readonly selectedLogs = computed(() =>
    (this.byContainer()[this.containers.selectedContainerId()] ?? []).slice()
      .sort((a, b) => b.ts - a.ts));

  private readonly inFlight = new Set<string>();

  constructor(private readonly ctx: StoreContext, private readonly containers: ContainerStore) {
    // a fresh selection has its own loading/error story, and its tail is fetched
    // immediately instead of waiting out the 5s poll
    containers.onSelect(() => {
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
        [c.id]: lines.map(l => ({ ...l, agentId: null })),
      }));
      if (this.isSelected(c.id)) this.updatedAt.set(Date.now());
    } catch (e) {
      const detail = (e as { message?: string } | null)?.message ?? 'log refresh failed';
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
