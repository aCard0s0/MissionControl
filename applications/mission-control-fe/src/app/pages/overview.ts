import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { Sparkline } from '../shared/sparkline';
import { Gauge } from '../shared/gauge';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { ago, clock, mb, until, uptime } from '../core/format';

@Component({
  selector: 'mc-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Sparkline, Gauge, StatusDot, RollingNumber, Reveal],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class OverviewPage {
  protected readonly store = inject(HermesStore);

  protected readonly uptime = uptime;
  protected readonly ago = ago;
  protected readonly until = until;
  protected readonly clock = clock;
  protected readonly mb = mb;

  protected readonly c = this.store.selectedContainer;

  protected readonly agentCounts = computed(() => {
    const as = this.store.containerAgents();
    return {
      active: as.filter(a => a.state === 'active').length,
      idle: as.filter(a => a.state === 'idle').length,
      dormant: as.filter(a => a.state === 'dormant').length,
    };
  });

  protected readonly recentLogs = computed(() => this.store.containerLogs().slice(0, 12));
  protected readonly errorCount = computed(() =>
    this.store.containerLogs().filter(l => l.level === 'error').length);

  protected readonly jobStats = computed(() => {
    const js = this.store.containerJobs();
    const next = js.filter(j => j.enabled).sort((a, b) => a.nextRun - b.nextRun)[0] ?? null;
    return { total: js.length, enabled: js.filter(j => j.enabled).length, failed: js.filter(j => j.lastStatus === 'fail').length, next };
  });

  protected readonly mcpStats = computed(() => {
    const servers = this.store.containerAgents().flatMap(a => a.mcp);
    return {
      connected: servers.filter(m => m.status === 'connected').length,
      errored: servers.filter(m => m.status === 'error').length,
    };
  });

  protected readonly comms = computed(() => {
    const map = new Map<string, { kind: string; status: string }>();
    for (const a of this.store.containerAgents()) {
      for (const i of a.integrations) {
        const prev = map.get(i.kind);
        // worst status wins the chip
        const rank: Record<string, number> = { down: 3, degraded: 2, up: 1, off: 0 };
        if (!prev || rank[i.status] > rank[prev.status]) map.set(i.kind, { kind: i.kind, status: i.status });
      }
    }
    return [...map.values()].sort((a, b) => a.kind.localeCompare(b.kind));
  });
}
