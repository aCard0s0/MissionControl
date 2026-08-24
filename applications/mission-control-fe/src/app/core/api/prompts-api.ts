import { PromptInput } from '../models';
import { ApiPrompt } from './api-types';
import { ApiHttp, seg } from './http';

/** `/api/prompts` — the prompt library, global rather than scoped to a container. */
export class PromptsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiPrompt[]> {
    return this.http.get('/api/prompts');
  }

  create(input: PromptInput): Promise<ApiPrompt> {
    return this.http.post('/api/prompts', input);
  }

  update(id: string, input: PromptInput): Promise<ApiPrompt> {
    return this.http.put(`/api/prompts/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/prompts/${seg(id)}`);
  }
}
