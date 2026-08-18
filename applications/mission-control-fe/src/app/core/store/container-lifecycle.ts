import { ContainerStatus, HermesContainer } from '../models';
import { AgentStore } from './agent-store';
import { BoardStore } from './board-store';
import { ContainerStore } from './container-store';
import { ImageCatalogStore } from './image-catalog-store';
import { JobStore } from './job-store';
import { LogStore } from './log-store';
import { StoreContext, nid } from './store-context';
import { WebhookStore } from './webhook-store';

/**
 * Deploying, starting, updating and removing a container, and the fan-out those
 * cause: profiles, jobs, board tasks, webhooks and log buffers are all keyed by
 * container id, so this is where the cascades live rather than in any one slice.
 */
export class ContainerLifecycle {
  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
    private readonly logs: LogStore,
    private readonly jobs: JobStore,
    private readonly board: BoardStore,
    private readonly webhooks: WebhookStore,
    private readonly images: ImageCatalogStore,
  ) {}

  /** Deploys a container and resolves only after refreshed inventory contains it. */
  async deploy(name: string, version: string, profileNames: string[], hostId = 'dh-local'): Promise<string> {
    if (!this.ctx.mock) {
      try {
        const r = await this.ctx.api.containers.deploy(hostId, name, version, profileNames);
        await new Promise(resolve => setTimeout(resolve, 600));
        await this.containers.refresh();
        this.containers.select(r.id);
        return r.id;
      } catch (e) {
        this.ctx.toastFailure('deploy', e);
        return '';
      }
    }
    const id = nid('c');
    const container: HermesContainer = {
      id, name, shortId: Math.random().toString(16).slice(2, 9), hostId, status: 'running',
      image: 'nousresearch/hermes-agent', version,
      startedAt: Date.now(),
      cpu: 8, ram: 512, ramTotal: 4096, disk: 1.2, diskTotal: 40,
      netIn: 5, netOut: 2,
      cpuHist: Array(60).fill(8), ramHist: Array(60).fill(512), netHist: Array(60).fill(7),
    };
    this.containers.containers.update(cs => [...cs, container]);
    this.logs.seed(id, [{
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: `container deployed (${version})`,
    }]);
    for (const profile of profileNames.filter(Boolean)) {
      void this.agents.create(id, profile, 'anthropic', 'claude-fable-5', 'sk-ant-new');
    }
    return id;
  }

  setStatus(id: string, status: ContainerStatus): void {
    if (!this.ctx.mock) {
      const container = this.containers.byId(id);
      if (!container) return;
      const call = status === 'running'
        ? this.ctx.api.containers.start(container.hostId, id)
        : this.ctx.api.containers.stop(container.hostId, id);
      call
        .then(() => setTimeout(() => this.containers.refresh(), 700))
        .catch(e => this.ctx.toastFailure(status === 'running' ? 'start' : 'stop', e));
      return;
    }
    this.containers.patch(id, c => ({
      ...c, status,
      startedAt: status === 'running' ? Date.now() : c.startedAt,
      ...(status === 'stopped' ? { cpu: 0, ram: 0, netIn: 0, netOut: 0 } : {}),
    }));
    this.logs.append(id, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: status === 'running' ? 'container started' : `container ${status}`,
    });
    if (status === 'stopped') this.agents.markDormant(id);
  }

  /**
   * Recreates `id` on `version`. The backend pulls the tag if needed, then
   * replaces the container against the same data volume, so profiles, souls,
   * skills and credentials survive. **The container id changes** — callers
   * holding an id must re-read it. Resolves to the new id, or '' on failure.
   */
  async update(id: string, version: string): Promise<string> {
    const container = this.containers.byId(id);
    if (!container || !version || version === container.version) return '';
    const wasSelected = this.containers.selectedContainerId() === id;

    if (!this.ctx.mock) {
      try {
        const r = await this.ctx.api.containers.update(container.hostId, id, version);
        await this.containers.refresh();
        if (wasSelected) this.containers.select(r.id);
        void this.images.refresh(container.hostId, true);   // the tag is pulled now
        return r.id;
      } catch (e) {
        this.ctx.toastFailure('update', e);
        await this.containers.refresh();   // the recreate may have half-landed
        return '';
      }
    }

    const newId = nid('c');
    this.containers.patch(id, c => ({
      ...c, id: newId, shortId: Math.random().toString(16).slice(2, 9),
      version, status: 'running', startedAt: Date.now(),
      cpuHist: [], ramHist: [], netHist: [],   // fresh container, no telemetry history
    }));
    // everything keyed by container id follows the new identity; agent ids are
    // stable because the profiles live in the volume that was reattached
    this.agents.reassignContainer(id, newId);
    this.jobs.reassignContainer(id, newId);
    this.board.reassignContainer(id, newId);
    this.logs.reassign(id, newId);
    this.logs.append(newId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: `container recreated on ${version} — data volume reattached`,
    });
    if (wasSelected) this.containers.select(newId);
    return newId;
  }

  async remove(id: string): Promise<boolean> {
    if (!this.ctx.mock) {
      const container = this.containers.byId(id);
      if (!container) return false;
      try {
        await this.ctx.api.containers.remove(container.hostId, id);
        if (this.containers.selectedContainerId() === id) this.containers.selectedContainerId.set('');
        await this.containers.refresh();
        return true;
      } catch (e) {
        this.ctx.toastFailure('remove', e);
        await this.containers.refresh(); // removal may have succeeded before volume cleanup failed
        return false;
      }
    }
    const agentIds = this.agents.dropContainer(id);
    this.containers.containers.update(cs => cs.filter(c => c.id !== id));
    this.jobs.dropByContainer(id);
    this.board.dropByContainer(id);
    this.webhooks.dropByAgents(agentIds);
    if (this.containers.selectedContainerId() === id) {
      this.containers.selectedContainerId.set(this.containers.containers()[0]?.id ?? '');
    }
    return true;
  }
}
