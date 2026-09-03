import { Injectable, computed, inject } from '@angular/core';
import { AgentStore } from './agent-store';
import { BoardStore } from './board-store';
import { ContainerStore } from './container-store';
import { HostStore } from './host-store';
import { ImageCatalogStore } from './image-catalog-store';
import { InferenceEndpointStore } from './inference-endpoint-store';
import { JobStore } from './job-store';
import { WebhookStore } from './webhook-store';
import { LogStore } from './log-store';
import { McpCatalogStore } from './mcp-catalog-store';
import { CredentialStore } from './credential-store';
import { ProviderStore } from './provider-store';
import { StoreContext } from './store-context';
import { SkillStore } from './skill-store';
import { TemplateStore } from './template-store';

/** Poll periods, in ms. Deliberately staggered: container state moves fastest,
 *  published image tags change on the order of days and each lookup probes the
 *  daemon, so that one is far slower than everything else. */
const POLL = {
  containers: 10_000,
  agents: 12_000,
  // a schedule only changes when a job runs or an operator edits one, and reading it
  // is one exec per profile — the slowest useful period, not the fastest
  jobs: 30_000,
  imageCatalogs: 300_000,
  stats: 3_000,
  logs: 5_000,
  // 'connected' is a claim about now. The domain polls all keep their last state quietly on
  // failure, so without this the banner could never come back once the first probe succeeded
  health: 10_000,
} as const;

/** How long to wait before retrying an unreachable backend. */
const RETRY_MS = 10_000;

/**
 * The store's clock: probes the backend, loads everything once it answers, and
 * then keeps each domain fresh on its own period.
 */
@Injectable({ providedIn: 'root' })
export class LiveSync {
  /** Banner text shown app-wide while there is no working backend. */
  readonly notice = computed(() => {
    switch (this.ctx.backendStatus()) {
      case 'connected': return null;
      case 'connecting': return 'connecting to backend…';
      case 'unreachable':
        return this.ctx.config.apiBaseUrl
          ? `backend unreachable at ${this.ctx.config.apiBaseUrl}, retrying…`
          : 'backend unreachable (is mission-control-server running?), retrying…';
    }
  });

  private started = false;

  /** Every registered poll, so a tab returning to the foreground can catch up the
   *  ones that came due while it was hidden. */
  private readonly polls: { run: () => void; periodMs: number; lastRun: number }[] = [];

  private readonly ctx = inject(StoreContext);
  private readonly hosts = inject(HostStore);
  private readonly containers = inject(ContainerStore);
  private readonly agents = inject(AgentStore);
  private readonly logs = inject(LogStore);
  private readonly board = inject(BoardStore);
  private readonly templates = inject(TemplateStore);
  private readonly mcp = inject(McpCatalogStore);
  private readonly credentials = inject(CredentialStore);
  private readonly skills = inject(SkillStore);
  private readonly providers = inject(ProviderStore);
  private readonly endpoints = inject(InferenceEndpointStore);
  private readonly images = inject(ImageCatalogStore);
  private readonly jobs = inject(JobStore);
  private readonly webhooks = inject(WebhookStore);

  async probeBackend(): Promise<void> {
    try {
      await this.ctx.api.health();
      this.ctx.backendStatus.set('connected');
      await this.start();
    } catch {
      this.ctx.backendStatus.set('unreachable');
      setTimeout(() => this.probeBackend(), RETRY_MS);
    }
  }

  private async start(): Promise<void> {
    if (this.started) return;
    this.started = true;
    // What is loaded here is what something other than its own page reads: a dialog, a
    // panel, a picker. The libraries whose only reader is their own page — prompts, guides
    // and the three group families — are not, because that page loads them when it opens and
    // loading them here as well meant a deep link to it fetched each one twice at once.
    await Promise.all([
      this.hosts.refresh(), this.endpoints.refresh(), this.providers.refreshRegistry(),
      this.containers.refresh(), this.board.refresh(), this.templates.refresh(),
      this.skills.refresh(), this.credentials.refresh(),
      this.mcp.refresh(), this.mcp.refreshRetainedResources(),
    ]);
    await this.agents.refresh();   // needs the container list
    void this.images.refreshAll();
    void this.jobs.refresh();      // needs the profile list
    void this.webhooks.refresh();
    this.schedule(() => this.containers.refresh(), POLL.containers);
    this.schedule(() => this.agents.refresh(), POLL.agents);
    this.schedule(() => this.jobs.refresh(), POLL.jobs);
    this.schedule(() => this.webhooks.refresh(), POLL.jobs);
    this.schedule(() => this.images.refreshAll(), POLL.imageCatalogs);
    this.schedule(() => this.containers.pollStats(), POLL.stats);
    this.schedule(() => this.logs.poll(), POLL.logs);
    this.schedule(() => this.checkHealth(), POLL.health);
    document.addEventListener('visibilitychange', () => this.catchUp());
    void this.containers.pollStats();
    void this.logs.poll();
  }

  /**
   * The probe again, on a period. A backend that died mid-session otherwise stayed
   * 'connected' forever: every domain poll swallows its failure and keeps its last state
   * (deliberately — see {@link StoreContext.toastFailure}), so nothing else ever says the
   * screen has gone stale. Recovery flips the banner back off; the domain polls were firing
   * throughout, so the next tick of each is what re-fills the data.
   */
  private async checkHealth(): Promise<void> {
    try {
      await this.ctx.api.health();
      this.ctx.backendStatus.set('connected');
    } catch {
      this.ctx.backendStatus.set('unreachable');
    }
  }

  /**
   * Registers a poll that runs on its own period while the tab is in the foreground.
   *
   * <p>A hidden tab polls nothing. Every period here ends at a Docker daemon, so a
   * dashboard left open in a background tab otherwise keeps that daemon — and a
   * laptop battery — busy answering questions nobody is reading. Stats alone is a
   * request per running container every 3 seconds.
   */
  private schedule(run: () => void, periodMs: number): void {
    const poll = { run, periodMs, lastRun: Date.now() };
    this.polls.push(poll);
    setInterval(() => {
      if (document.hidden) return;
      poll.lastRun = Date.now();
      run();
    }, periodMs);
  }

  /**
   * Runs the polls that came due while the tab was hidden, so the first paint after
   * a tab switch is fresh rather than as stale as the last foreground tick left it.
   *
   * <p>Only the ones actually due: waking all seven on every visibility change would
   * turn alt-tabbing into a burst of daemon calls, which is the cost this is here to
   * avoid in the first place.
   */
  private catchUp(): void {
    if (document.hidden) return;
    const now = Date.now();
    for (const poll of this.polls) {
      if (now - poll.lastRun < poll.periodMs) continue;
      poll.lastRun = now;
      poll.run();
    }
  }
}
