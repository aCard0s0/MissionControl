import { PromptGroupInput } from '../models';
import { ApiPromptGroup } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp } from './http';

/**
 * `/api/prompt-groups` — how the prompt library is filed: a named set of prompts.
 *
 * Four routes and no fifth, for the reason {@link PromptsApi} gives: neither one prompt nor a
 * set of them ever reaches a container, so there is nothing here to deploy.
 */
export class PromptGroupsApi extends CrudApi<ApiPromptGroup, PromptGroupInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/prompt-groups');
  }
}
