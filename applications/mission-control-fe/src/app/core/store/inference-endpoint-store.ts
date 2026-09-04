import { inject, Injectable, signal } from '@angular/core';
import { EndpointModel, InferenceEndpoint, PullState, RunningModel } from '../models';
import { StoreContext } from './store-context';
import { toEndpointModel, toInferenceEndpoint, toPullState, toRunningModel } from './wire-mappers';

/**
 * Self-hosted inference endpoints — a url you run — and the models on them.
 *
 * <p>Split from {@link ProviderStore}, which holds the LLM *vendor* registry. They sat in one
 * class with `refresh()` loading endpoints and `refreshRegistry()` loading vendors, one letter
 * of context apart, over an api client where every endpoint call read `providers.list()`.
 * The Models page uses only this half; the create-agent pickers merge both, which is the
 * `providerOptions()` call, not a reason to share a store.
 */
@Injectable({ providedIn: 'root' })
export class InferenceEndpointStore {
  readonly endpoints = signal<InferenceEndpoint[]>([]);

  private readonly ctx = inject(StoreContext);

  async refresh(): Promise<void> {
    try {
      this.endpoints.set((await this.ctx.api.endpoints.list()).map(toInferenceEndpoint));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  add(name: string, url: string): void {
    this.ctx.api.endpoints.add(name, url)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('add endpoint', e));
  }

  remove(id: string): void {
    this.ctx.api.endpoints.remove(id)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('remove endpoint', e));
  }

  check(id: string): void {
    this.endpoints.update(ps => ps.map(p => p.id === id ? { ...p, status: 'unknown' as const } : p));
    this.ctx.api.endpoints.check(id)
      .then(endpoint => this.endpoints.update(
        ps => ps.map(p => p.id === id ? toInferenceEndpoint(endpoint) : p)))
      .catch(e => {
        this.ctx.toastFailure('endpoint check', e);
        this.refresh();
      });
  }

  /** The models an endpoint lists. Rejects on failure rather than degrading to an empty
   *  list: the Models page renders the reason inline where the list would be, and the create
   *  dialog's picker holds its own fallback — a toast from here preempted both, and once it
   *  faded the panel claimed "0 listed" for an endpoint that was merely unreachable. */
  models(id: string): Promise<EndpointModel[]> {
    return this.ctx.api.endpoints.models(id).then(list => list.map(toEndpointModel));
  }

  /** What the endpoint is holding in memory. Empty on failure — the panel polls this, so a
   *  transient read must not toast on every tick. */
  running(id: string): Promise<RunningModel[]> {
    return this.ctx.api.endpoints.running(id)
      .then(list => list.map(toRunningModel))
      .catch(() => []);
  }

  loadModel(id: string, name: string): Promise<void> {
    return this.ctx.api.endpoints.loadModel(id, name)
      .catch(e => this.ctx.toastFailure('model load', e));
  }

  unloadModel(id: string, name: string): Promise<void> {
    return this.ctx.api.endpoints.unloadModel(id, name)
      .catch(e => this.ctx.toastFailure('model unload', e));
  }

  pullModel(id: string, name: string): Promise<void> {
    return this.ctx.api.endpoints.pullModel(id, name)
      .catch(e => this.ctx.toastFailure('pull', e));
  }

  deleteModel(id: string, name: string): Promise<void> {
    return this.ctx.api.endpoints.deleteModel(id, name)
      .catch(e => this.ctx.toastFailure('model delete', e));
  }

  pullStatus(id: string): Promise<PullState[]> {
    return this.ctx.api.endpoints.pullStatus(id)
      .then(list => list.map(toPullState))
      .catch(() => []);
  }
}
