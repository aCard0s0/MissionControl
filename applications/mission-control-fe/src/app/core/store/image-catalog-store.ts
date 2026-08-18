import { signal } from '@angular/core';
import { ApiImageTags } from '../hermes-api';
import { ImageCatalog } from '../models';
import { seedImageTags } from '../mock-data';
import { ContainerStore } from './container-store';
import { HostStore } from './host-store';
import { StoreContext } from './store-context';
import { toImageCatalog } from './wire-mappers';

/** Published tags change on the order of days, and each lookup probes the
 *  daemon — so a cached catalog is reused for this long. */
const CATALOG_TTL = 300_000;

/**
 * Merged registry + local image tags per docker host, behind a TTL cache.
 * Advisory data: a failed refresh keeps the last catalog and never toasts.
 */
export class ImageCatalogStore {
  readonly catalog = signal<Record<string, ImageCatalog>>({});

  private readonly inFlight = new Set<string>();

  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly hosts: HostStore,
  ) {}

  /** Raw tag list for the deploy picker, which shows remote tags too. */
  tags(hostId: string): Promise<ApiImageTags> {
    if (this.ctx.mock) {
      const catalog = this.mockCatalog(hostId);
      return Promise.resolve({
        repository: catalog.repository,
        tags: catalog.tags.map(t => t.tag),
        entries: catalog.tags.map(t => ({ tag: t.tag, pulled: t.pulled, remote: true })),
      });
    }
    return this.ctx.api.containers.imageTags(hostId);
  }

  async refresh(hostId: string, force = false): Promise<void> {
    if (!hostId || this.inFlight.has(hostId)) return;
    const known = this.catalog()[hostId];
    if (!force && known && Date.now() - known.fetchedAt < CATALOG_TTL) return;
    this.inFlight.add(hostId);
    try {
      const catalog = this.ctx.mock
        ? this.mockCatalog(hostId)
        : toImageCatalog(await this.ctx.api.containers.imageTags(hostId));
      this.catalog.update(m => ({ ...m, [hostId]: catalog }));
    } catch {
      /* registry or daemon hiccup — keep the last catalog */
    } finally {
      this.inFlight.delete(hostId);
    }
  }

  /** Refreshes every connected host that actually runs containers. */
  async refreshAll(force = false): Promise<void> {
    const hosted = new Set(this.containers.containers().map(c => c.hostId));
    const ids = this.hosts.hosts()
      .filter(h => h.status === 'connected' && hosted.has(h.id))
      .map(h => h.id);
    await this.ctx.mapPool(ids, 4, id => this.refresh(id, force));
  }

  private mockCatalog(hostId: string): ImageCatalog {
    const containers = this.containers.containers();
    const running = new Set(containers.filter(c => c.hostId === hostId).map(c => c.version));
    return {
      repository: containers[0]?.image ?? 'nousresearch/hermes-agent',
      tags: seedImageTags().map(tag => ({ tag, pulled: tag === 'latest' || running.has(tag) })),
      registryStatus: 'ok',
      fetchedAt: Date.now(),
    };
  }
}
