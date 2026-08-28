import {
  ApiModelCatalog, ApiModelProvider, ApiEndpointModel, ApiInferenceEndpoint, ApiPullState,
  ApiRunningModel,
} from './api-types';
import { ApiHttp, seg } from './http';

/**
 * Two related registries:
 * - `/api/providers` + `/api/models` — the LLM provider registry and its model
 *   catalogs, which drive the create-agent and template pickers;
 * - `/api/inference-endpoints` — self-hosted inference endpoints, whose models Mission
 *   Control can list, pull, delete and load into memory.
 *
 * The two axes are not variants of each other: a provider is an upstream vendor and a
 * capability description, an endpoint is a URL you run. This class still spans both; the
 * method names read `providers.*` for the endpoint calls, which is the next thing to fix.
 */
export class ProvidersApi {
  constructor(private readonly http: ApiHttp) {}

  /** The LLM vendor registry — single source of truth for the picker. Not the endpoints. */
  registry(): Promise<ApiModelProvider[]> {
    return this.http.get('/api/providers');
  }

  modelCatalog(provider: string): Promise<ApiModelCatalog> {
    return this.http.get(`/api/models/${seg(provider)}`);
  }

  /** Reads the catalog straight from the provider API using a caller-held key. */
  modelCatalogLive(provider: string, apiKey: string): Promise<ApiModelCatalog> {
    return this.http.post(`/api/models/${seg(provider)}`, { apiKey });
  }

  list(): Promise<ApiInferenceEndpoint[]> {
    return this.http.get('/api/inference-endpoints');
  }

  add(name: string, url: string): Promise<ApiInferenceEndpoint> {
    return this.http.post('/api/inference-endpoints', { name, url });
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/inference-endpoints/${seg(id)}`);
  }

  check(id: string): Promise<ApiInferenceEndpoint> {
    return this.http.post(`/api/inference-endpoints/${seg(id)}/check`);
  }

  models(id: string): Promise<ApiEndpointModel[]> {
    return this.http.get(`/api/inference-endpoints/${seg(id)}/models`);
  }

  /** What the endpoint is holding in memory. Empty for a protocol that cannot report it. */
  running(id: string): Promise<ApiRunningModel[]> {
    return this.http.get(`/api/inference-endpoints/${seg(id)}/running`);
  }

  /** Loads a model and pins it there. Slow by nature — the weights come off disk first. */
  loadModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/inference-endpoints/${seg(id)}/models/load`, { name });
  }

  unloadModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/inference-endpoints/${seg(id)}/models/unload`, { name });
  }

  pullModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/inference-endpoints/${seg(id)}/models/pull`, { name });
  }

  pullStatus(id: string): Promise<ApiPullState[]> {
    return this.http.get(`/api/inference-endpoints/${seg(id)}/pulls`);
  }

  deleteModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/inference-endpoints/${seg(id)}/models/delete`, { name });
  }
}
