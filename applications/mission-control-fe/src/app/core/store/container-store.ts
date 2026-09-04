import { computed, inject, Injectable, Signal, signal, WritableSignal } from '@angular/core';
import { ApiStats } from '../hermes-api';
import { mapPool } from '../map-pool';
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

  /**
   * The container every page reads through. Readable here, written only by {@link select} —
   * the listeners below are the reason. A public writable signal is a second way to change the
   * selection that skips them, and `refresh` was taking it: a container recreated under a new
   * id by an upgrade moved the selection without telling the caches keyed by the old one, so
   * the jobs, logs and webhooks on screen stayed the previous container's until their next
   * poll.
   */
  readonly selectedContainerId: Signal<string>;

  private readonly selectedId = signal('');

  readonly selected = computed(() =>
    this.containers().find(c => c.id === this.selectedContainerId()) ?? null);

  readonly fleetHealth = computed<ContainerStatus>(() => {
    const cs = this.containers();
    if (cs.some(c => c.status === 'unhealthy')) return 'unhealthy';
    if (cs.some(c => c.status === 'running')) return 'running';
    return 'stopped';
  });

  /** Notified after every selection — see {@link select}. */
  private readonly selectionListeners: Array<(id: string) => void> = [];
  private readonly netMeta = new Map<string, { rx: number; tx: number; at: number }>();
  private statsInFlight = false;
  /** Bumped per {@link refresh}; a response that is no longer the newest issued is dropped.
   *  A sequence rather than the skip-a-tick guard the other slices use, because a lifecycle
   *  action's refresh must actually run — skipping it while a slow poll is in flight would
   *  hand that action's `select` an inventory the poll is about to overwrite. */
  private refreshSeq = 0;

  private readonly ctx = inject(StoreContext);

  constructor() {
    this.containers = signal([]);
    this.selectedContainerId = this.selectedId.asReadonly();
  }

  byId = (id: string): HermesContainer | null => this.containers().find(c => c.id === id) ?? null;

  /** Runs `listener` whenever the selection changes, so container-keyed caches can reset
   *  without this store having to know about them.
   *
   *  A listener list rather than an `effect` on the signal: these want *changes*, and an
   *  effect also runs once on the value it starts with — three slices would each need their
   *  own previous-value guard to un-say that. */
  onSelect(listener: (id: string) => void): void {
    this.selectionListeners.push(listener);
  }

  /** The one writer. Every path that moves the selection — an operator's click, a lifecycle
   *  action, a refresh finding the selected container gone — comes through here, so no path
   *  can move it silently. */
  select(id: string): void {
    // no equality guard: re-picking the container already selected is what re-reads its log
    // tail whole, and LogStore says why it must
    this.selectedId.set(id);
    for (const listener of this.selectionListeners) listener(id);
  }

  async refresh(): Promise<void> {
    const seq = ++this.refreshSeq;
    try {
      const list = await this.ctx.api.containers.list();
      // Superseded while in flight: a deploy's refresh landed after this one was issued and
      // already applied a fresher inventory. Applying this response would resurrect the older
      // list — without the just-deployed container — and the auto-select below would then move
      // the selection off it, resetting every container-keyed cache to the wrong container.
      if (seq !== this.refreshSeq) return;
      this.containers.update(prev => {
        const prevById = new Map(prev.map(c => [c.id, c]));
        return list.map(c => {
          const old = prevById.get(c.id);
          return {
            id: c.id, name: c.name, shortId: c.shortId, hostId: c.hostId,
            status: c.status, image: c.image, version: c.version,
            imageDigest: c.imageDigest ?? null, release: c.release ?? null, startedAt: c.startedAt,
            disk: c.sizeRootFsGb ?? 0, diskTotal: 0,   // daemons report size, not quota
            cpu: old?.cpu ?? 0, ram: old?.ram ?? 0, ramTotal: old?.ramTotal ?? 0,
            netIn: old?.netIn ?? 0, netOut: old?.netOut ?? 0,
            cpuHist: old?.cpuHist ?? [], ramHist: old?.ramHist ?? [], netHist: old?.netHist ?? [],
          };
        });
      });
      // the network baseline is keyed by container id and read only to derive a rate
      // from the previous sample, so an id the daemon no longer lists is dead weight
      const listed = new Set(list.map(c => c.id));
      for (const id of [...this.netMeta.keys()]) if (!listed.has(id)) this.netMeta.delete(id);
      // the selected id can also go stale — an updated container is recreated
      // under a new id, and out-of-band removals happen too. Never clear on a
      // transient empty inventory.
      if (list.length && !list.some(c => c.id === this.selectedContainerId())) {
        this.select(list[0].id);
      }
    } catch { /* keep last inventory */ }
  }

  /**
   * Per-container CPU/RAM/network samples, folded into the sparkline history.
   *
   * <p>One request per host, not per container. Asking per container meant a request each,
   * every one of them blocked for the second or two the daemon spends taking the two samples
   * a CPU delta needs — so a tick cost grew with the fleet, and past six containers the pool
   * could no longer finish inside the three-second period and ticks were quietly dropped. The
   * server holds the streams now and answers all of them from memory.
   */
  async pollStats(): Promise<void> {
    if (this.statsInFlight) return;   // skip a tick rather than overlap fan-outs
    this.statsInFlight = true;
    try {
      const running = this.containers().filter(c => c.status === 'running' || c.status === 'unhealthy');
      const byHost = new Map<string, string[]>();
      for (const c of running) byHost.set(c.hostId, [...(byHost.get(c.hostId) ?? []), c.id]);
      await mapPool([...byHost], 4, async ([hostId, ids]) => {
        try {
          const samples = await this.ctx.api.containers.statsBatch(hostId, ids);
          for (const [id, s] of Object.entries(samples)) this.applySample(id, s);
        } catch { /* host may have gone away between polls */ }
      });
    } finally {
      this.statsInFlight = false;
    }
  }

  /** Folds one sample in, deriving network rates from the previous one. */
  private applySample(id: string, s: ApiStats): void {
    const prev = this.netMeta.get(id);
    this.netMeta.set(id, { rx: s.rxBytes, tx: s.txBytes, at: s.sampledAt });
    const dt = prev ? (s.sampledAt - prev.at) / 1000 : 0;
    const netIn = prev && dt > 0 ? Math.max(0, (s.rxBytes - prev.rx) / dt / 1024) : 0;
    const netOut = prev && dt > 0 ? Math.max(0, (s.txBytes - prev.tx) / dt / 1024) : 0;
    this.patch(id, x => ({
      ...x, cpu: s.cpuPercent, ram: s.ramMb, ramTotal: s.ramTotalMb, netIn, netOut,
      cpuHist: pushSample(x.cpuHist, s.cpuPercent),
      ramHist: pushSample(x.ramHist, s.ramMb),
      netHist: pushSample(x.netHist, netIn + netOut),
    }));
  }

  /** Rewrites one container in place; a no-op if it is already gone. */
  patch(id: string, change: (container: HermesContainer) => HermesContainer): void {
    this.containers.update(cs => cs.map(c => c.id === id ? change(c) : c));
  }
}
