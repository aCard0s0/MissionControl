import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { DockerHost } from '../models';
import { StoreContext } from './store-context';
import { toDockerHost } from './wire-mappers';

/**
 * The docker daemons Mission Control deploys to, and their reachability. Every
 * write goes to the backend, and the answer is what lands in the signal — the
 * daemon decides what is reachable, so nothing here guesses.
 */
@Injectable({ providedIn: 'root' })
export class HostStore {
  readonly hosts: WritableSignal<DockerHost[]>;

  /** Worst-of summary across docker hosts, for the sidebar chip. */
  readonly overall = computed(() => {
    const hs = this.hosts();
    if (hs.some(h => h.status === 'error')) return 'error';
    if (hs.some(h => h.status === 'connecting')) return 'connecting';
    if (hs.some(h => h.status === 'connected')) return 'connected';
    return 'disconnected';
  });

  private readonly ctx = inject(StoreContext);

  constructor() {
    // the local socket, as the backend will describe it once it answers
    this.hosts = signal([{
      id: 'dh-local', name: 'localhost', url: this.ctx.config.dockerSocket, kind: 'local',
      status: 'disconnected', engine: null, apiVersion: null, latencyMs: null,
      note: 'waiting for backend connection',
    }]);
  }

  byId = (id: string): DockerHost | null => this.hosts().find(h => h.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.hosts.set((await this.ctx.api.hosts.list()).map(toDockerHost));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  add(name: string, url: string): void {
    this.ctx.api.hosts.add(name, url)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('add host', e));
  }

  remove(id: string): void {
    const host = this.byId(id);
    if (!host) {
      this.ctx.gone('docker host');
      return;
    }
    if (host.kind === 'local') return;   // not removable, and the page offers no button
    this.ctx.api.hosts.remove(id)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('remove host', e));
  }

  check(id: string): void {
    this.hosts.update(hs => hs.map(h => h.id === id ? { ...h, status: 'connecting' as const } : h));
    this.ctx.api.hosts.check(id)
      .then(host => this.hosts.update(hs => hs.map(h => h.id === id ? toDockerHost(host) : h)))
      .catch(e => {
        this.ctx.toastFailure('host check', e);
        this.refresh();
      });
  }
}
