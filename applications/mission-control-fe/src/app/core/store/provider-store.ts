import { inject, Injectable, signal } from '@angular/core';
import {
  LlmProvider, InferenceEndpoint, EndpointModel, ModelCatalog, PullState, RunningModel,
} from '../models';
import { FALLBACK_MODELS } from './provider-defaults';
import { StoreContext } from './store-context';
import {
  toLlmProvider, toInferenceEndpoint, toEndpointModel, toPullState, toRunningModel,
} from './wire-mappers';

/**
 * Two registries the UI keeps side by side, and they are not the same axis:
 * - `llmProviders` — model *vendors* who can serve an Agent, and their catalogs;
 * - `endpoints` — self-hosted inference endpoints (a URL you run) whose models
 *   Mission Control can list, pull, delete, load and unload.
 */
@Injectable({ providedIn: 'root' })
export class ProviderStore {
  /** LLM provider registry for the create-agent / template pickers. Starts empty
   *  and is filled by `refreshRegistry()`, which `LiveSync.start()` awaits — the
   *  backend's list is the only one, so there is nothing to seed it with. A hardcoded
   *  mirror used to live here and had drifted to 12 of the registry's 32 entries,
   *  offering a short list nobody could tell from the real one. */
  readonly llmProviders = signal<LlmProvider[]>([]);

  readonly endpoints = signal<InferenceEndpoint[]>([]);

  private readonly ctx = inject(StoreContext);

  /** Loads the LLM provider registry. Swallows a failure rather than rejecting:
   *  `LiveSync.start()` awaits this inside a `Promise.all`, so throwing here would
   *  take every other initial load with it. An empty picker is what an unreachable
   *  backend looks like everywhere else in this app. */
  async refreshRegistry(): Promise<void> {
    try {
      this.llmProviders.set((await this.ctx.api.providers.registry()).map(toLlmProvider));
    } catch { /* no registry — the picker stays empty, like every other store */ }
  }

  async refresh(): Promise<void> {
    try {
      this.endpoints.set((await this.ctx.api.providers.list()).map(toInferenceEndpoint));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  add(name: string, url: string): void {
    this.ctx.api.providers.add(name, url)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('add provider', e));
  }

  remove(id: string): void {
    this.ctx.api.providers.remove(id)
      .then(() => this.refresh())
      .catch(e => this.ctx.toastFailure('remove provider', e));
  }

  check(id: string): void {
    this.endpoints.update(ps => ps.map(p => p.id === id ? { ...p, status: 'unknown' as const } : p));
    this.ctx.api.providers.check(id)
      .then(provider => this.endpoints.update(
        ps => ps.map(p => p.id === id ? toInferenceEndpoint(provider) : p)))
      .catch(e => {
        this.ctx.toastFailure('provider check', e);
        this.refresh();
      });
  }

  models(id: string): Promise<EndpointModel[]> {
    return this.ctx.api.providers.models(id)
      .then(list => list.map(toEndpointModel))
      .catch(e => {
        this.ctx.toastFailure('model list', e);
        return [];
      });
  }

  /** What the endpoint is holding in memory. Empty on failure — the panel polls this, so a
   *  transient read must not toast on every tick. */
  running(id: string): Promise<RunningModel[]> {
    return this.ctx.api.providers.running(id)
      .then(list => list.map(toRunningModel))
      .catch(() => []);
  }

  loadModel(id: string, name: string): Promise<void> {
    return this.ctx.api.providers.loadModel(id, name)
      .catch(e => this.ctx.toastFailure('model load', e));
  }

  unloadModel(id: string, name: string): Promise<void> {
    return this.ctx.api.providers.unloadModel(id, name)
      .catch(e => this.ctx.toastFailure('model unload', e));
  }

  pullModel(id: string, name: string): Promise<void> {
    return this.ctx.api.providers.pullModel(id, name)
      .catch(e => this.ctx.toastFailure('pull', e));
  }

  deleteModel(id: string, name: string): Promise<void> {
    return this.ctx.api.providers.deleteModel(id, name)
      .catch(e => this.ctx.toastFailure('model delete', e));
  }

  pullStatus(id: string): Promise<PullState[]> {
    return this.ctx.api.providers.pullStatus(id)
      .then(list => list.map(toPullState))
      .catch(() => []);
  }

  /** Models a provider key can serve, from the backend's configured catalog. Carries the
   *  backend's own `source` through, and labels the offline fallback `bundled` — a shipped
   *  list and a list read from the provider are indistinguishable in a dropdown otherwise. */
  async modelCatalog(provider: string): Promise<ModelCatalog> {
    try {
      const answered = await this.ctx.api.providers.modelCatalog(provider);
      return { models: answered.models, source: answered.source };
    } catch {
      return { models: FALLBACK_MODELS[provider] ?? [], source: 'bundled' };
    }
  }

  /** Fetch the catalog straight from the provider API using a key. */
  async modelCatalogLive(provider: string, apiKey: string): Promise<ModelCatalog> {
    try {
      const answered = await this.ctx.api.providers.modelCatalogLive(provider, apiKey);
      return { models: answered.models, source: answered.source };
    } catch {
      return this.modelCatalog(provider);
    }
  }
}
