import { DockerHost } from '../models';
import { ApiHttp, seg } from './http';

/** `/api/hosts` — the docker daemons Mission Control drives. */
export class HostsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<DockerHost[]> {
    return this.http.get('/api/hosts');
  }

  add(name: string, url: string): Promise<DockerHost> {
    return this.http.post('/api/hosts', { name, url });
  }

  check(id: string): Promise<DockerHost> {
    return this.http.post(`/api/hosts/${seg(id)}/check`);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/hosts/${seg(id)}`);
  }
}
