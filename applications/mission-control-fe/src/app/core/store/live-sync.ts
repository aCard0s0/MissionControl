import { computed } from '@angular/core';
import { AgentStore } from './agent-store';
import { BoardStore } from './board-store';
import { ContainerStore } from './container-store';
import { HostStore } from './host-store';
import { ImageCatalogStore } from './image-catalog-store';
import { JobStore } from './job-store';
import { LogStore } from './log-store';
import { McpCatalogStore } from './mcp-catalog-store';
import { ProviderStore } from './provider-store';
import { StoreContext } from './store-context';
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
} as const;

/** How long to wait before retrying an unreachable backend. */
const RETRY_MS = 10_000;

/**
 * The store's clock: probes the backend, loads everything once it answers, and
 * then keeps each domain fresh on its own period.
 */
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

  constructor(
    private readonly ctx: StoreContext,
    private readonly hosts: HostStore,
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
    private readonly logs: LogStore,
    private readonly board: BoardStore,
    private readonly templates: TemplateStore,
    private readonly mcp: McpCatalogStore,
    private readonly providers: ProviderStore,
    private readonly images: ImageCatalogStore,
    private readonly jobs: JobStore,
  ) {}

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
    await Promise.all([
      this.hosts.refresh(), this.providers.refresh(), this.providers.refreshRegistry(),
      this.containers.refresh(), this.board.refresh(), this.templates.refresh(),
      this.mcp.refresh(), this.mcp.refreshRetainedResources(),
    ]);
    await this.agents.refresh();   // needs the container list
    void this.images.refreshAll();
    void this.jobs.refresh();      // needs the profile list
    setInterval(() => this.containers.refresh(), POLL.containers);
    setInterval(() => this.agents.refresh(), POLL.agents);
    setInterval(() => this.jobs.refresh(), POLL.jobs);
    setInterval(() => this.images.refreshAll(), POLL.imageCatalogs);
    setInterval(() => this.containers.pollStats(), POLL.stats);
    setInterval(() => this.logs.poll(), POLL.logs);
    void this.containers.pollStats();
    void this.logs.poll();
  }
}
