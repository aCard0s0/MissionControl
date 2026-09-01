import { PromptGroupInput } from '../models';
import { ApiPromptGroup } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/prompt-groups` — how the prompt library is filed: a named set of prompts.
 *
 * Four routes and no fifth. A prompt is text for a person to paste, so neither one nor a set
 * of them ever reaches a container — there is nothing here to deploy.
 */
export class PromptGroupsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiPromptGroup[]> {
    return this.http.get('/api/prompt-groups');
  }

  create(input: PromptGroupInput): Promise<ApiPromptGroup> {
    return this.http.post('/api/prompt-groups', input);
  }

  update(id: string, input: PromptGroupInput): Promise<ApiPromptGroup> {
    return this.http.put(`/api/prompt-groups/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/prompt-groups/${seg(id)}`);
  }
}
