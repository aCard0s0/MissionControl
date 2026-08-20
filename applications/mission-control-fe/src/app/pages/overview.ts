import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { JobStore } from '../core/store/job-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { LogStore } from '../core/store/log-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { AgentProfile, HermesContainer, ImageTag } from '../core/models';
import { containerUpdate } from './containers';
import { Sparkline } from '../shared/sparkline';
import { Gauge } from '../shared/gauge';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { LogView } from '../shared/log-view';
import { TerminalIcon } from '../shared/terminal-icon';
import { ago, mb, until, uptime } from '../core/format';

@Component({
  selector: 'mc-overview',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, Sparkline, Gauge, StatusDot, RollingNumber, Reveal, TerminalIcon, LogView],
  templateUrl: './overview.html',
  styleUrl: './overview.scss',
})
export class OverviewPage {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly jobs = inject(JobStore);
  protected readonly logs = inject(LogStore);
  protected readonly images = inject(ImageCatalogStore);
  protected readonly terminal = inject(TerminalRequestStore);
  private readonly router = inject(Router);

  protected readonly uptime = uptime;
  protected readonly ago = ago;
  protected readonly until = until;
  protected readonly mb = mb;

  protected readonly c = this.containers.selected;

  /**
   * The image this container could move to, or null.
   *
   * <p>Shown here as well as on the card because the overview is where an operator lands to
   * ask why an Agent is behaving oddly, and "the image is two months old" is an answer that
   * page should not make them go looking for.
   */
  protected readonly update = computed<ImageTag | null>(() => {
    const container = this.c();
    return container ? containerUpdate(container, this.images.catalog()[container.hostId]) : null;
  });

  protected updateHint(c: HermesContainer, target: ImageTag): string {
    return target.tag === c.version
      ? `a newer image was published on ${c.version}`
      : `${c.version} → ${target.tag}`;
  }

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

  constructor() {
    // the store's TTL collapses this with the containers page's own refresh
    void this.images.refreshAll();
  }

  /** A summary card is itself the link to the page it summarizes — see overview.html. */
  protected go(path: string): void {
    void this.router.navigate([path]);
  }

  /** Open the terminal panel on a shell already inside this profile's session. */
  protected openAgentShell(a: AgentProfile): void {
    const c = this.c();
    if (c) this.terminal.openAgentShell(a, c);
  }

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

  /** The window the card shows; the level filter inside {@link LogView} narrows it further. */
  protected readonly tailWindow = computed(() => this.logs.selectedLogs().slice(0, 40));

  /** Counted over the window rather than the whole tail, so the chip matches what is on screen. */
  protected readonly errorCount = computed(() =>
    this.tailWindow().filter(l => l.level === 'error').length);

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
