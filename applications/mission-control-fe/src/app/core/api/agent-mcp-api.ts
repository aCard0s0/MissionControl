import { ApiAgentProfile, ApiMcpTestResult } from './api-types';
import { AgentRef, agentPath } from './agent-ref';
import { ApiHttp, seg } from './http';

/** One MCP server as the profile's `config.yaml` records it. */
export interface AgentMcpRequest {
  name: string;
  transport: string;
  url?: string;
  command?: string;
  args?: string;
  enabled?: boolean;
  headers?: Record<string, string>;
}

/** `/api/agents/**\/mcp` — the servers a single profile connects to. Mutations
 *  answer with the refreshed profile; only `test` reports its own result. */
export class AgentMcpApi {
  constructor(private readonly http: ApiHttp) {}

  add(ref: AgentRef, request: AgentMcpRequest): Promise<ApiAgentProfile> {
    return this.http.post(`${agentPath(ref)}/mcp`, request);
  }

  update(ref: AgentRef, oldServerName: string, request: AgentMcpRequest): Promise<ApiAgentProfile> {
    return this.http.put(this.path(ref, oldServerName), request);
  }

  setEnabled(ref: AgentRef, serverName: string, enabled: boolean): Promise<ApiAgentProfile> {
    return this.http.put(`${this.path(ref, serverName)}/enabled`, { enabled });
  }

  /** Links a catalog entry into this profile under `alias`. */
  connectCatalog(ref: AgentRef, serverId: string, alias: string): Promise<ApiAgentProfile> {
    return this.http.post(`${agentPath(ref)}/mcp/catalog`, { serverId, alias });
  }

  /** Re-applies the catalog definition onto an already linked alias. */
  syncCatalog(ref: AgentRef, alias: string): Promise<ApiAgentProfile> {
    return this.http.post(`${this.path(ref, alias)}/sync`);
  }

  /** Detaches the alias from the catalog, leaving a directly-edited server. */
  unlinkCatalog(ref: AgentRef, alias: string): Promise<ApiAgentProfile> {
    return this.http.delete(`${this.path(ref, alias)}/link`);
  }

  remove(ref: AgentRef, serverName: string): Promise<ApiAgentProfile> {
    return this.http.delete(this.path(ref, serverName));
  }

  test(ref: AgentRef, serverName: string): Promise<ApiMcpTestResult> {
    return this.http.post(`${this.path(ref, serverName)}/test`);
  }

  private path(ref: AgentRef, serverName: string): string {
    return `${agentPath(ref)}/mcp/${seg(serverName)}`;
  }
}
