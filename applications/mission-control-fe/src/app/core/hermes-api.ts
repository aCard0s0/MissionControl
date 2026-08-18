import { AgentsApi } from './api/agents-api';
import { BoardApi } from './api/board-api';
import { ContainersApi } from './api/containers-api';
import { HostsApi } from './api/hosts-api';
import { ApiHttp } from './api/http';
import { McpCatalogApi } from './api/mcp-catalog-api';
import { ProvidersApi } from './api/providers-api';
import { TemplatesApi } from './api/templates-api';

// Typed client for mission-control-server. apiBaseUrl '' = same origin
// (the combined image); a non-empty base supports split deployments.
//
// The wire shapes and the per-resource clients live under ./api; this file only
// wires them onto one object so callers read as `api.agents.updateSoul(...)`.
export * from './api/api-types';
export type { AgentRef } from './api/agent-ref';
export type { AgentMcpRequest } from './api/agent-mcp-api';
export type { CreateAgentRequest } from './api/agents-api';
export type { McpServerOperation } from './api/mcp-catalog-api';

export class HermesApi {
  private readonly http: ApiHttp;

  readonly hosts: HostsApi;
  readonly containers: ContainersApi;
  readonly agents: AgentsApi;
  readonly mcp: McpCatalogApi;
  readonly providers: ProvidersApi;
  readonly templates: TemplatesApi;
  readonly board: BoardApi;

  /** `http` is the seam mock data mode substitutes — see {@link MockHttp}. */
  constructor(apiBaseUrl: string, http: ApiHttp = new ApiHttp(apiBaseUrl)) {
    this.http = http;
    this.hosts = new HostsApi(this.http);
    this.containers = new ContainersApi(this.http);
    this.agents = new AgentsApi(this.http);
    this.mcp = new McpCatalogApi(this.http);
    this.providers = new ProvidersApi(this.http);
    this.templates = new TemplatesApi(this.http);
    this.board = new BoardApi(this.http);
  }

  health(): Promise<{ status: string; version: string; dockerConnected: boolean }> {
    return this.http.get('/health');
  }
}
