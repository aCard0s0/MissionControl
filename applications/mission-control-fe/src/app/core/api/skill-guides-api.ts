import { AgentRef } from './agent-ref';
import { SkillGuideInput } from '../models';
import { ApiDeployedGuide, ApiSkillGuide } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/skill-guides` — guides: prose that composes several library skills with the MCP
 * servers they need, and one deploy that puts the whole set on an agent.
 */
export class SkillGuidesApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiSkillGuide[]> {
    return this.http.get('/api/skill-guides');
  }

  create(input: SkillGuideInput): Promise<ApiSkillGuide> {
    return this.http.post('/api/skill-guides', input);
  }

  update(id: string, input: SkillGuideInput): Promise<ApiSkillGuide> {
    return this.http.put(`/api/skill-guides/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/skill-guides/${seg(id)}`);
  }

  /** Answers with a row per part, because a guide can half-land. */
  deploy(id: string, agent: AgentRef): Promise<ApiDeployedGuide> {
    return this.http.post(`/api/skill-guides/${seg(id)}/deploy`, {
      hostId: agent.hostId,
      containerId: agent.containerId,
      profile: agent.name,
    });
  }
}
