import { inject, Injectable, signal } from '@angular/core';
import { LlmProvider, InferenceEndpoint, EndpointModel, PullState } from '../models';
import { DEFAULT_LLM_PROVIDERS, FALLBACK_MODELS } from './provider-defaults';
import { StoreContext } from './store-context';
import { toLlmProvider, toInferenceEndpoint, toEndpointModel, toPullState } from './wire-mappers';

/**
 * Two registries the UI keeps side by side, and they are not the same axis:
 * - `llmProviders` — model *vendors* who can serve an Agent, and their catalogs;
 * - `endpoints` — self-hosted inference endpoints (a URL you run) whose models
 *   Mission Control can list, pull and delete.
 */
@Injectable({ providedIn: 'root' })
export class ProviderStore {
  /** LLM provider registry for the create-agent / template pickers. Seeded with
   *  the bootstrap mirror, refreshed from the backend in live mode. */
  readonly llmProviders = signal<LlmProvider[]>(DEFAULT_LLM_PROVIDERS);

  readonly endpoints = signal<InferenceEndpoint[]>([]);

  private readonly ctx = inject(StoreContext);

  /** Loads the LLM provider registry; keeps the bootstrap mirror on failure. */
  async refreshRegistry(): Promise<void> {
    try {
      const list = await this.ctx.api.providers.registry();
      if (list.length) this.llmProviders.set(list.map(toLlmProvider));
    } catch { /* keep DEFAULT_LLM_PROVIDERS */ }
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

  /** Models a provider key can serve, from the backend's configured catalog. */
  async modelCatalog(provider: string): Promise<string[]> {
    const fallback = FALLBACK_MODELS[provider] ?? [];
    try {
      return (await this.ctx.api.providers.modelCatalog(provider)).models;
    } catch {
      return fallback;
    }
  }

  /** Fetch the catalog straight from the provider API using a key. */
  async modelCatalogLive(provider: string, apiKey: string): Promise<string[]> {
    try {
      return (await this.ctx.api.providers.modelCatalogLive(provider, apiKey)).models;
    } catch {
      return this.modelCatalog(provider);
    }
  }
}
