import { AgentRef } from './agent-ref';
import { McpGroupInput } from '../models';
import { ApiDeployedMcpGroup, ApiMcpGroup } from './api-types';
import { ApiHttp, seg } from './http';

/**
 * `/api/mcp-groups` — a named set of catalog entries, and one call that connects the whole set
 * to one agent.
 *
 * The only group client with a deploy. Nothing here records which agents a group reaches: the
 * list answers that, read back off the agent links each time.
 */
export class McpGroupsApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiMcpGroup[]> {
    return this.http.get('/api/mcp-groups');
  }

  create(input: McpGroupInput): Promise<ApiMcpGroup> {
    return this.http.post('/api/mcp-groups', input);
  }

  update(id: string, input: McpGroupInput): Promise<ApiMcpGroup> {
    return this.http.put(`/api/mcp-groups/${seg(id)}`, input);
  }

  remove(id: string): Promise<void> {
    return this.http.delete(`/api/mcp-groups/${seg(id)}`);
  }

  /** Answers a row per server, because a group can half-connect. */
  deploy(id: string, agent: AgentRef): Promise<ApiDeployedMcpGroup> {
    return this.http.post(`/api/mcp-groups/${seg(id)}/deploy`, {
      hostId: agent.hostId,
      containerId: agent.containerId,
      profile: agent.name,
    });
  }
}
