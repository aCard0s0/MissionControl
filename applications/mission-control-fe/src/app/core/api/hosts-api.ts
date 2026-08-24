import { ApiDockerHost } from './api-types';
import { ApiHttp, seg } from './http';

/** `/api/hosts` — the docker daemons Mission Control drives. */
export class HostsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiDockerHost[]> {
    return this.http.get('/api/hosts');
  }

  add(name: string, url: string): Promise<ApiDockerHost> {
    return this.http.post('/api/hosts', { name, url });
  }

  check(id: string): Promise<ApiDockerHost> {
    return this.http.post(`/api/hosts/${seg(id)}/check`);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/hosts/${seg(id)}`);
  }
}
