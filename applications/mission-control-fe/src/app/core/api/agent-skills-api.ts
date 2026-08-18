import { ApiAgentProfile, ApiSkillContent } from './api-types';
import { AgentRef, agentPath } from './agent-ref';
import { ApiHttp, seg } from './http';

/** `/api/agents/**\/skills` — every call answers with the refreshed profile. */
export class AgentSkillsApi {
  constructor(private readonly http: ApiHttp) {}

  setEnabled(ref: AgentRef, skillName: string, enabled: boolean): Promise<ApiAgentProfile> {
    return this.http.put(`${this.path(ref, skillName)}`, { enabled });
  }

  install(ref: AgentRef, skillName: string): Promise<ApiAgentProfile> {
    return this.http.post(`${agentPath(ref)}/skills`, { name: skillName });
  }

  uninstall(ref: AgentRef, skillName: string): Promise<ApiAgentProfile> {
    return this.http.delete(this.path(ref, skillName));
  }

  content(ref: AgentRef, skillName: string): Promise<ApiSkillContent> {
    return this.http.get(`${this.path(ref, skillName)}/content`);
  }

  updateContent(ref: AgentRef, skillName: string, body: string): Promise<ApiAgentProfile> {
    return this.http.put(`${this.path(ref, skillName)}/content`, { body });
  }

  private path(ref: AgentRef, skillName: string): string {
    return `${agentPath(ref)}/skills/${seg(skillName)}`;
  }
}
