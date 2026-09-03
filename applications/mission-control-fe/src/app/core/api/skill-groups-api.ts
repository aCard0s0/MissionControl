import { SkillGroupInput } from '../models';
import { ApiSkillGroup } from './api-types';
import { CrudApi } from './crud-api';
import { ApiHttp } from './http';

/**
 * `/api/skill-groups` — how the skill library is filed: a named set of skills and,
 * optionally, the guide that explains it.
 *
 * No deploy here, unlike skills and guides. A group is organization; the guide it points at
 * is what pushes a set at an agent.
 */
export class SkillGroupsApi extends CrudApi<ApiSkillGroup, SkillGroupInput> {
  constructor(http: ApiHttp) {
    super(http, '/api/skill-groups');
  }
}
