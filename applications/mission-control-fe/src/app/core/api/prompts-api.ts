import { PromptInput } from '../models';
import { ApiPrompt } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp } from './http';

/** `/api/prompts` — the prompt library, global rather than scoped to a container.
 *  Four routes and no fifth: a prompt is text for a person to paste, so none of it
 *  ever reaches a container. */
export class PromptsApi extends CrudApi<ApiPrompt, PromptInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/prompts');
  }
}
