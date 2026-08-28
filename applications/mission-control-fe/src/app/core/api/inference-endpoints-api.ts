import {
  ApiEndpointModel, ApiInferenceEndpoint, ApiPullState, ApiRunningModel,
} from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/inference-endpoints` — self-hosted model servers Mission Control administers:
 * ollama on this machine, a Mac across the LAN, a rented box.
 *
 * <p>Split from {@link ProvidersApi}, which serves the LLM *vendor* registry. The two are
 * not variants of each other — a provider is a capability description, an endpoint is a url
 * you run — and while they shared a class every endpoint call read as `providers.list()`.
 * The backend route and table carried the same confusion until they were renamed off
 * `model-providers`.
 */
export class InferenceEndpointsApi {
  constructor(private readonly http: ApiHttp) {}

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
