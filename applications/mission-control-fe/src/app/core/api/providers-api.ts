import {
  ApiModelCatalog, ApiModelProvider, ApiEndpointModel, ApiInferenceEndpoint, ApiPullState,
  ApiRunningModel,
} from './api-types';
import { ApiHttp, seg } from './http';

/**
 * Two related registries:
 * - `/api/providers` + `/api/models` — the LLM provider registry and its model
 *   catalogs, which drive the create-agent and template pickers;
 * - `/api/model-providers` — self-hosted inference endpoints, whose models Mission
 *   Control can list, pull, delete and load into memory. (Route kept for compatibility;
 *   the concept is an endpoint, not a vendor.)
 */
export class ProvidersApi {
  constructor(private readonly http: ApiHttp) {}

  /** The model-provider registry (single source of truth for the picker). */
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
    return this.http.get('/api/model-providers');
  }

  add(name: string, url: string): Promise<ApiInferenceEndpoint> {
    return this.http.post('/api/model-providers', { name, url });
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/model-providers/${seg(id)}`);
  }

  check(id: string): Promise<ApiInferenceEndpoint> {
    return this.http.post(`/api/model-providers/${seg(id)}/check`);
  }

  models(id: string): Promise<ApiEndpointModel[]> {
    return this.http.get(`/api/model-providers/${seg(id)}/models`);
  }

  /** What the endpoint is holding in memory. Empty for a protocol that cannot report it. */
  running(id: string): Promise<ApiRunningModel[]> {
    return this.http.get(`/api/model-providers/${seg(id)}/running`);
  }

  /** Loads a model and pins it there. Slow by nature — the weights come off disk first. */
  loadModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/model-providers/${seg(id)}/models/load`, { name });
  }

  unloadModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/model-providers/${seg(id)}/models/unload`, { name });
  }

  pullModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/model-providers/${seg(id)}/models/pull`, { name });
  }

  pullStatus(id: string): Promise<ApiPullState[]> {
    return this.http.get(`/api/model-providers/${seg(id)}/pulls`);
  }

  deleteModel(id: string, name: string): Promise<void> {
    return this.http.post(`/api/model-providers/${seg(id)}/models/delete`, { name });
  }
}
