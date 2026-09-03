import { CredentialInput } from '../models';
import { ApiCredential } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp } from './http';

/**
 * `/api/credentials` — keys and tokens saved once, to be offered wherever one is typed.
 *
 * CRUD only. No call here resolves a secret, so none can return one — the three writes that
 * take a credential id belong to the resources they write (an agent's `.env`, a new profile, a
 * blueprint), and each resolves the id server-side.
 */
export class CredentialsApi extends CrudApi<ApiCredential, CredentialInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/credentials');
  }
}
