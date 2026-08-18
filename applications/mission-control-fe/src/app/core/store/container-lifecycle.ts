import { ContainerStatus } from '../models';
import { ContainerStore } from './container-store';
import { ImageCatalogStore } from './image-catalog-store';
import { StoreContext } from './store-context';

/**
 * Deploying, starting, updating and removing a container. Each call is the
 * backend's to make; what lands here afterwards is a re-read of the inventory,
 * because the daemon decides what actually exists.
 */
export class ContainerLifecycle {
  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly images: ImageCatalogStore,
  ) {}

  /** Deploys a container and resolves only after refreshed inventory contains it. */
  async deploy(name: string, version: string, profileNames: string[], hostId = 'dh-local'): Promise<string> {
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

  setStatus(id: string, status: ContainerStatus): void {
    const container = this.containers.byId(id);
    if (!container) return;
    const call = status === 'running'
      ? this.ctx.api.containers.start(container.hostId, id)
      : this.ctx.api.containers.stop(container.hostId, id);
    call
      .then(() => setTimeout(() => this.containers.refresh(), 700))
      .catch(e => this.ctx.toastFailure(status === 'running' ? 'start' : 'stop', e));
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

  async remove(id: string): Promise<boolean> {
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
}
