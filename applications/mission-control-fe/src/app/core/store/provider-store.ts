import { WritableSignal, signal } from '@angular/core';
import { ApiModelProvider, ApiPullState } from '../hermes-api';
import { ModelProvider, OllamaModel } from '../models';
import { DEFAULT_LLM_PROVIDERS, FALLBACK_MODELS } from './mock-catalogs';
import { StoreContext, nid } from './store-context';

/**
 * Two provider registries the UI keeps side by side:
 * - `llmProviders` — who can serve a model to an Agent, and their catalogs;
 * - `ollamaProviders` — self-hosted ollama endpoints whose models Mission
 *   Control can list, pull and delete.
 */
export class ProviderStore {
  /** LLM provider registry for the create-agent / template pickers. Seeded with
   *  the bootstrap mirror, refreshed from the backend in live mode. */
  readonly llmProviders = signal<ApiModelProvider[]>(DEFAULT_LLM_PROVIDERS);

  readonly ollamaProviders: WritableSignal<ModelProvider[]>;

  constructor(private readonly ctx: StoreContext) {
    this.ollamaProviders = signal(ctx.mock
      ? [{
          id: 'mp-local', name: 'local ollama', url: 'http://host.docker.internal:11434',
          kind: 'ollama', status: 'connected', version: '0.6.x', detail: null,
        }]
      : []);
  }

  /** Loads the LLM provider registry; keeps the bootstrap mirror on failure. */
  async refreshRegistry(): Promise<void> {
    if (this.ctx.mock) return;
    try {
      const list = await this.ctx.api.providers.registry();
      if (list.length) this.llmProviders.set(list);
    } catch { /* keep DEFAULT_LLM_PROVIDERS */ }
  }

  async refresh(): Promise<void> {
    try {
      this.ollamaProviders.set(await this.ctx.api.providers.list());
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  add(name: string, url: string): void {
    if (!this.ctx.mock) {
      this.ctx.api.providers.add(name, url)
        .then(() => this.refresh())
        .catch(e => this.ctx.toastFailure('add provider', e));
      return;
    }
    const provider: ModelProvider = {
      id: nid('mp'), name, url, kind: 'ollama',
      status: 'unknown', version: null, detail: null,
    };
    this.ollamaProviders.update(ps => [...ps, provider]);
    this.probe(provider.id);
  }

  remove(id: string): void {
    if (!this.ctx.mock) {
      this.ctx.api.providers.remove(id)
        .then(() => this.refresh())
        .catch(e => this.ctx.toastFailure('remove provider', e));
      return;
    }
    this.ollamaProviders.update(ps => ps.filter(p => p.id !== id));
  }

  check(id: string): void {
    this.ollamaProviders.update(ps => ps.map(p => p.id === id ? { ...p, status: 'unknown' as const } : p));
    if (!this.ctx.mock) {
      this.ctx.api.providers.check(id)
        .then(provider => this.ollamaProviders.update(ps => ps.map(p => p.id === id ? provider : p)))
        .catch(e => {
          this.ctx.toastFailure('provider check', e);
          this.refresh();
        });
      return;
    }
    this.probe(id);
  }

  models(id: string): Promise<OllamaModel[]> {
    if (this.ctx.mock) {
      const yesterday = Date.now() - 86_400_000;
      return Promise.resolve([
        { name: 'gemma3:4b', sizeBytes: 3_300_000_000, family: 'gemma3', parameterSize: '4.3B', modifiedAt: yesterday },
        { name: 'qwen3:8b', sizeBytes: 5_200_000_000, family: 'qwen3', parameterSize: '8.2B', modifiedAt: yesterday },
      ]);
    }
    return this.ctx.api.providers.models(id).catch(e => {
      this.ctx.toastFailure('model list', e);
      return [];
    });
  }

  pullModel(id: string, name: string): Promise<void> {
    if (this.ctx.mock) {
      this.ctx.toast('mock mode — not pulling');
      return Promise.resolve();
    }
    return this.ctx.api.providers.pullModel(id, name)
      .catch(e => this.ctx.toastFailure('pull', e));
  }

  deleteModel(id: string, name: string): Promise<void> {
    if (this.ctx.mock) {
      this.ctx.toast('mock mode — not deleting');
      return Promise.resolve();
    }
    return this.ctx.api.providers.deleteModel(id, name)
      .catch(e => this.ctx.toastFailure('model delete', e));
  }

  pullStatus(id: string): Promise<ApiPullState[]> {
    if (this.ctx.mock) return Promise.resolve([]);
    return this.ctx.api.providers.pullStatus(id).catch(() => []);
  }

  /** Models a provider key can serve, from the backend's configured catalog. */
  async modelCatalog(provider: string): Promise<string[]> {
    const fallback = FALLBACK_MODELS[provider] ?? [];
    if (this.ctx.mock) return fallback;
    try {
      return (await this.ctx.api.providers.modelCatalog(provider)).models;
    } catch {
      return fallback;
    }
  }

  /** Fetch the catalog straight from the provider API using a key — live only. */
  async modelCatalogLive(provider: string, apiKey: string): Promise<string[]> {
    if (this.ctx.mock) return this.modelCatalog(provider);
    try {
      return (await this.ctx.api.providers.modelCatalogLive(provider, apiKey)).models;
    } catch {
      return this.modelCatalog(provider);
    }
  }

  /** Simulated ollama ping — mock mode only; live mode asks the backend. */
  private probe(id: string): void {
    setTimeout(() => {
      this.ollamaProviders.update(ps => ps.map(p => {
        if (p.id !== id) return p;
        const ok = Math.random() > 0.15;
        return ok
          ? { ...p, status: 'connected' as const, version: '0.6.x', detail: null }
          : { ...p, status: 'error' as const, version: null,
              detail: 'connection refused — is ollama listening on that address?' };
      }));
    }, 800);
  }
}
