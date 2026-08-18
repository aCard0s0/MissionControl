import { ApiContainer, ApiImageTags, ApiLogLine, ApiStats } from './api-types';
import { ApiHttp, seg } from './http';

/** `/api/containers` and `/api/images` — Agent container inventory, telemetry
 *  and lifecycle on a given docker host. */
export class ContainersApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiContainer[]> {
    return this.http.get('/api/containers');
  }

  stats(hostId: string, id: string): Promise<ApiStats> {
    return this.http.get(`/api/containers/${hostId}/${id}/stats`);
  }

  logs(hostId: string, id: string, tail = 100): Promise<ApiLogLine[]> {
    return this.http.get(`/api/containers/${hostId}/${id}/logs?tail=${tail}`);
  }

  deploy(hostId: string, name: string, version: string, profiles: string[]): Promise<{ id: string }> {
    return this.http.post('/api/containers', { hostId, name, version, profiles });
  }

  start(hostId: string, id: string): Promise<void> {
    return this.http.post(`/api/containers/${seg(hostId)}/${seg(id)}/start`);
  }

  stop(hostId: string, id: string): Promise<void> {
    return this.http.post(`/api/containers/${seg(hostId)}/${seg(id)}/stop`);
  }

  remove(hostId: string, id: string): Promise<void> {
    return this.http.delete(`/api/containers/${seg(hostId)}/${seg(id)}`);
  }

  /**
   * Recreates the container on `version`, reusing its data volume, and resolves
   * to the replacement's id. Allowed far longer than the default budget: a cold
   * host pulls the image first, then the new container has to pass readiness.
   */
  update(hostId: string, id: string, version: string): Promise<{ id: string }> {
    return this.http.post(
      `/api/containers/${seg(hostId)}/${seg(id)}/update`, { version }, 300_000);
  }

  imageTags(hostId: string): Promise<ApiImageTags> {
    return this.http.get(`/api/images/tags?hostId=${seg(hostId)}`);
  }
}
