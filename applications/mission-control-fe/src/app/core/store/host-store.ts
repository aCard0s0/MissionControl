import { WritableSignal, computed, signal } from '@angular/core';
import { DockerHost } from '../models';
import { seedDockerHosts } from '../mock-data';
import { StoreContext, nid } from './store-context';

/** The docker daemons Mission Control deploys to, and their reachability. */
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

  constructor(private readonly ctx: StoreContext) {
    this.hosts = signal(ctx.mock
      ? seedDockerHosts(ctx.config.dockerSocket)
      : [{
          id: 'dh-local', name: 'localhost', url: ctx.config.dockerSocket, kind: 'local',
          status: 'disconnected', engine: null, apiVersion: null, latencyMs: null,
          note: 'waiting for backend connection',
        }]);
  }

  byId = (id: string): DockerHost | null => this.hosts().find(h => h.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.hosts.set(await this.ctx.api.hosts.list());
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  add(name: string, url: string): void {
    if (!this.ctx.mock) {
      this.ctx.api.hosts.add(name, url)
        .then(() => this.refresh())
        .catch(e => this.ctx.toastFailure('add host', e));
      return;
    }
    const host: DockerHost = {
      id: nid('dh'), name, url, kind: 'remote',
      status: 'connecting', engine: null, apiVersion: null, latencyMs: null, note: null,
    };
    this.hosts.update(hs => [...hs, host]);
    this.probe(host.id);
  }

  remove(id: string): void {
    const host = this.byId(id);
    if (!host || host.kind === 'local') return;   // local socket is not removable
    if (!this.ctx.mock) {
      this.ctx.api.hosts.remove(id)
        .then(() => this.refresh())
        .catch(e => this.ctx.toastFailure('remove host', e));
      return;
    }
    this.hosts.update(hs => hs.filter(h => h.id !== id));
  }

  check(id: string): void {
    this.hosts.update(hs => hs.map(h => h.id === id ? { ...h, status: 'connecting' as const } : h));
    if (!this.ctx.mock) {
      this.ctx.api.hosts.check(id)
        .then(host => this.hosts.update(hs => hs.map(h => h.id === id ? host : h)))
        .catch(e => {
          this.ctx.toastFailure('host check', e);
          this.refresh();
        });
      return;
    }
    this.probe(id);
  }

  /** Simulated daemon ping — mock mode only; live mode asks the backend. */
  private probe(id: string): void {
    setTimeout(() => {
      this.hosts.update(hs => hs.map(h => {
        if (h.id !== id) return h;
        const ok = h.kind === 'local' || Math.random() > 0.15;
        return ok
          ? { ...h, status: 'connected' as const, engine: 'Docker 27.3', apiVersion: '1.47',
              latencyMs: h.kind === 'local' ? 2 : 18 + Math.floor(Math.random() * 90), note: null }
          : { ...h, status: 'error' as const, engine: null, apiVersion: null, latencyMs: null,
              note: 'connection refused — check the daemon address and TLS setup' };
      }));
    }, 800);
  }
}
