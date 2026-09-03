import { CredentialInput } from '../models';
import { ApiCredential } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/credentials` — keys and tokens saved once, to be offered wherever one is typed.
 *
 * CRUD only. No call here resolves a secret, so none can return one — the three writes that
 * take a credential id belong to the resources they write (an agent's `.env`, a new profile, a
 * blueprint), and each resolves the id server-side.
 */
export class CredentialsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiCredential[]> {
    return this.http.get('/api/credentials');
  }

  create(input: CredentialInput): Promise<ApiCredential> {
    return this.http.post('/api/credentials', input);
  }

  update(id: string, input: CredentialInput): Promise<ApiCredential> {
    return this.http.put(`/api/credentials/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/credentials/${seg(id)}`);
  }
}
