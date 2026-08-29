import { AgentRef } from './agent-ref';
import { SkillInput } from '../models';
import { ApiImportedSkill, ApiSkill, ApiAgentProfile } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/skills` — the skill *library*: dashboard-owned rows, global rather than scoped
 * to a container.
 *
 * Not to be confused with `api.agents.*Skill*`, which reads and edits the skills already
 * installed on one profile. This client holds skills that may live on no agent at all.
 */
export class SkillsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiSkill[]> {
    return this.http.get('/api/skills');
  }

  create(input: SkillInput): Promise<ApiSkill> {
    return this.http.post('/api/skills', input);
  }

  update(id: string, input: SkillInput): Promise<ApiSkill> {
    return this.http.put(`/api/skills/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/skills/${seg(id)}`);
  }

  /** Puts the skill on one agent. A hub row installs through hermes; a local one has its
   *  files written out. Answers with the refreshed profile, like every agent mutation. */
  deploy(id: string, agent: AgentRef): Promise<ApiAgentProfile> {
    return this.http.post(`/api/skills/${seg(id)}/deploy`, {
      hostId: agent.hostId,
      containerId: agent.containerId,
      profile: agent.name,
    });
  }

  /** Copies a skill off an agent into the library. */
  importFrom(agent: AgentRef, skillName: string): Promise<ApiImportedSkill> {
    return this.http.post('/api/skills/import', {
      hostId: agent.hostId,
      containerId: agent.containerId,
      profile: agent.name,
      skillName,
    });
  }
}
