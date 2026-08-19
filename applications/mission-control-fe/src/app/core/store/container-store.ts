import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { ContainerStatus, HermesContainer } from '../models';
import { StoreContext } from './store-context';

/** Keeps the last 60 samples — one minute at the 1s-ish poll rate. */
const pushSample = (history: number[], value: number): number[] => [...history.slice(-59), value];

/**
 * The Agent container inventory and the single selected container every page
 * reads through. Selection lives here (not in a page) because the "never mix
 * containers" rule is a store-level guarantee.
 */
@Injectable({ providedIn: 'root' })
export class ContainerStore {
  readonly containers: WritableSignal<HermesContainer[]>;
  readonly selectedContainerId: WritableSignal<string>;

  readonly selected = computed(() =>
    this.containers().find(c => c.id === this.selectedContainerId()) ?? null);

  readonly fleetHealth = computed<ContainerStatus>(() => {
    const cs = this.containers();
    if (cs.some(c => c.status === 'unhealthy')) return 'unhealthy';
    if (cs.some(c => c.status === 'running')) return 'running';
    return 'stopped';
  });

  /** Notified after every explicit selection — see {@link select}. */
  private readonly selectionListeners: Array<(id: string) => void> = [];
  private readonly netMeta = new Map<string, { rx: number; tx: number; at: number }>();
  private statsInFlight = false;

  private readonly ctx = inject(StoreContext);

  constructor() {
    this.containers = signal([]);
    this.selectedContainerId = signal('');
  }

  byId = (id: string): HermesContainer | null => this.containers().find(c => c.id === id) ?? null;

  /** Runs `listener` whenever the operator (or a lifecycle action) picks a
   *  container, so container-keyed caches can reset without this store having to
   *  know about them. */
  onSelect(listener: (id: string) => void): void {
    this.selectionListeners.push(listener);
  }

  select(id: string): void {
    this.selectedContainerId.set(id);
    for (const listener of this.selectionListeners) listener(id);
  }

  async refresh(): Promise<void> {
    try {
      const list = await this.ctx.api.containers.list();
      this.containers.update(prev => {
        const prevById = new Map(prev.map(c => [c.id, c]));
        return list.map(c => {
          const old = prevById.get(c.id);
          return {
            id: c.id, name: c.name, shortId: c.shortId, hostId: c.hostId,
            status: c.status, image: c.image, version: c.version, startedAt: c.startedAt,
            disk: c.sizeRootFsGb ?? 0, diskTotal: 0,   // daemons report size, not quota
            cpu: old?.cpu ?? 0, ram: old?.ram ?? 0, ramTotal: old?.ramTotal ?? 0,
            netIn: old?.netIn ?? 0, netOut: old?.netOut ?? 0,
            cpuHist: old?.cpuHist ?? [], ramHist: old?.ramHist ?? [], netHist: old?.netHist ?? [],
          };
        });
      });
      // the selected id can also go stale — an updated container is recreated
      // under a new id, and out-of-band removals happen too. Never clear on a
      // transient empty inventory.
      if (list.length && !list.some(c => c.id === this.selectedContainerId())) {
        this.selectedContainerId.set(list[0].id);
      }
    } catch { /* keep last inventory */ }
  }

  /** Per-container CPU/RAM/network sample, folded into the sparkline history. */
  async pollStats(): Promise<void> {
    if (this.statsInFlight) return;   // skip a tick rather than overlap fan-outs
    this.statsInFlight = true;
    try {
      const running = this.containers().filter(c => c.status === 'running' || c.status === 'unhealthy');
      await this.ctx.mapPool(running, 6, async c => {
        try {
          const s = await this.ctx.api.containers.stats(c.hostId, c.id);
          const prev = this.netMeta.get(c.id);
          this.netMeta.set(c.id, { rx: s.rxBytes, tx: s.txBytes, at: s.sampledAt });
          const dt = prev ? (s.sampledAt - prev.at) / 1000 : 0;
          const netIn = prev && dt > 0 ? Math.max(0, (s.rxBytes - prev.rx) / dt / 1024) : 0;
          const netOut = prev && dt > 0 ? Math.max(0, (s.txBytes - prev.tx) / dt / 1024) : 0;
          this.patch(c.id, x => ({
            ...x, cpu: s.cpuPercent, ram: s.ramMb, ramTotal: s.ramTotalMb, netIn, netOut,
            cpuHist: pushSample(x.cpuHist, s.cpuPercent),
            ramHist: pushSample(x.ramHist, s.ramMb),
            netHist: pushSample(x.netHist, netIn + netOut),
          }));
        } catch { /* container may have stopped between polls */ }
      });
    } finally {
      this.statsInFlight = false;
    }
  }

  /** Rewrites one container in place; a no-op if it is already gone. */
  patch(id: string, change: (container: HermesContainer) => HermesContainer): void {
    this.containers.update(cs => cs.map(c => c.id === id ? change(c) : c));
  }
}
