import { ProfileTemplateInput } from '../models';
import { ApiAgentProfile, ApiProfileTemplate } from './api-types';
import { AgentRef } from './agent-ref';
import { ApiHttp, seg } from './http';

/** `/api/profile-templates` — reusable agent blueprints, plus the capture and
 *  deploy calls that move configuration between a template and a live profile. */
export class TemplatesApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiProfileTemplate[]> {
    return this.http.get('/api/profile-templates');
  }

  create(input: ProfileTemplateInput): Promise<ApiProfileTemplate> {
    return this.http.post('/api/profile-templates', input);
  }

  update(id: string, input: ProfileTemplateInput): Promise<ApiProfileTemplate> {
    return this.http.put(`/api/profile-templates/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/profile-templates/${seg(id)}`);
  }

  /** Snapshots a running profile into a new template. */
  capture(ref: AgentRef, templateName?: string): Promise<ApiProfileTemplate> {
    return this.http.post('/api/profile-templates/capture', {
      hostId: ref.hostId, containerId: ref.containerId, name: ref.name, templateName,
    });
  }

  /** Materializes a template into `ref`'s container as the profile `ref.name`. */
  deploy(id: string, ref: AgentRef): Promise<ApiAgentProfile> {
    return this.http.post(`/api/profile-templates/${seg(id)}/deploy`, {
      hostId: ref.hostId, containerId: ref.containerId, name: ref.name,
    });
  }
}
