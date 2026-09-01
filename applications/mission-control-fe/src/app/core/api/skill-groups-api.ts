import { SkillGroupInput } from '../models';
import { ApiSkillGroup } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/skill-groups` — how the skill library is filed: a named set of skills and,
 * optionally, the guide that explains it.
 *
 * No deploy here, unlike skills and guides. A group is organization; the guide it points at
 * is what pushes a set at an agent.
 */
export class SkillGroupsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiSkillGroup[]> {
    return this.http.get('/api/skill-groups');
  }

  create(input: SkillGroupInput): Promise<ApiSkillGroup> {
    return this.http.post('/api/skill-groups', input);
  }

  update(id: string, input: SkillGroupInput): Promise<ApiSkillGroup> {
    return this.http.put(`/api/skill-groups/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/skill-groups/${seg(id)}`);
  }
}
