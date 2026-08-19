import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { JobStore } from '../core/store/job-store';
import { LogStore } from '../core/store/log-store';
import { AgentProfile, LogEntry } from '../core/models';
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
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly jobs = inject(JobStore);
  protected readonly logs = inject(LogStore);

  protected readonly uptime = uptime;
  protected readonly ago = ago;
  protected readonly until = until;
  protected readonly clock = clock;
  protected readonly mb = mb;

  protected readonly c = this.containers.selected;

  protected readonly agentCounts = computed(() => {
    const as = this.agents.forSelectedContainer();
    return {
      active: as.filter(a => a.state === 'active').length,
      idle: as.filter(a => a.state === 'idle').length,
      dormant: as.filter(a => a.state === 'dormant').length,
    };
  });

  /** aggregate skill + MCP-tool counts across the container's agents */
  protected readonly skillToolCounts = computed(() => {
    const as = this.agents.forSelectedContainer();
    return {
      skills: as.reduce((n, a) => n + a.skills.length, 0),
      tools: as.reduce((n, a) => n + a.mcp.reduce(
        (t, m) => t + (m.status === 'connected' ? m.tools : 0), 0), 0),
    };
  });

  /** per-agent skill / MCP / tool tallies for the row badges; `custom` counts
   *  agent-authored skills (source 'user') so they stand out from bundled/hub */
  protected agentMeta(a: AgentProfile) {
    return {
      skills: a.skills.length,
      custom: a.skills.filter(s => s.source === 'user').length,
      mcp: a.mcp.length,
      tools: a.mcp.reduce((t, m) => t + (m.status === 'connected' ? m.tools : 0), 0),
    };
  }

  /** quick level filter for the log tail */
  protected readonly logLevel = signal<'all' | 'error' | 'warn'>('all');

  protected readonly tailWindow = computed(() => this.logs.selectedLogs().slice(0, 40));
  protected readonly recentLogs = computed(() => {
    const level = this.logLevel();
    return this.tailWindow()
      .filter(l => (level === 'all' ? true : l.level === level))
  });
  protected readonly errorCount = computed(() =>
    this.tailWindow().filter(l => l.level === 'error').length);

  protected logKey(l: LogEntry): string {
    return `${l.ts}:${l.level}:${l.source}:${l.msg}`;
  }

  protected readonly jobStats = computed(() => {
    const js = this.jobs.forSelectedContainer();
    const next = js.filter(j => j.enabled).sort((a, b) => a.nextRun - b.nextRun)[0] ?? null;
    return { total: js.length, enabled: js.filter(j => j.enabled).length, failed: js.filter(j => j.lastStatus === 'fail').length, next };
  });

  protected readonly mcpStats = computed(() => {
    const servers = this.agents.forSelectedContainer().flatMap(a => a.mcp);
    return {
      connected: servers.filter(m => m.status === 'connected').length,
      errored: servers.filter(m => m.status === 'error').length,
      unknown: servers.filter(m => m.status === 'unknown' || m.status === 'checking').length,
    };
  });

  protected readonly comms = computed(() => {
    const map = new Map<string, { kind: string; status: string }>();
    for (const a of this.agents.forSelectedContainer()) {
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
