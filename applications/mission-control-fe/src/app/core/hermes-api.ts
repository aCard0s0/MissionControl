import { AgentsApi } from './api/agents-api';
import { BoardApi } from './api/board-api';
import { ContainersApi } from './api/containers-api';
import { HostsApi } from './api/hosts-api';
import { ApiHttp } from './api/http';
import { terminalSocketUrl } from './api/terminal-socket';
import { McpCatalogApi } from './api/mcp-catalog-api';
import { PromptsApi } from './api/prompts-api';
import { ProvidersApi } from './api/providers-api';
import { ServerApi } from './api/server-api';
import { TemplatesApi } from './api/templates-api';

// Typed client for mission-control-server. apiBaseUrl '' = same origin
// (the combined image); a non-empty base supports split deployments.
//
// The wire shapes and the per-resource clients live under ./api; this file only
// wires them onto one object so callers read as `api.agents.updateSoul(...)`.
//
// It is also the one door into ./api for everything outside it: files under
// ./api import their siblings directly, everyone else takes the wire types here.
export * from './api/api-types';
export type { AgentRef } from './api/agent-ref';
export type { AgentMcpRequest } from './api/agent-mcp-api';
export type { CreateAgentRequest } from './api/agents-api';
export type { McpServerOperation } from './api/mcp-catalog-api';
export type { TerminalFrame } from './api/terminal-socket';
export { resizeFrame } from './api/terminal-socket';
export type { ApiServerInfo } from './api/server-api';

export class HermesApi {
  private readonly base: string;
  private readonly http: ApiHttp;

  readonly hosts: HostsApi;
  readonly containers: ContainersApi;
  readonly agents: AgentsApi;
  readonly mcp: McpCatalogApi;
  readonly prompts: PromptsApi;
  readonly providers: ProvidersApi;
  readonly templates: TemplatesApi;
  readonly board: BoardApi;
  readonly server: ServerApi;

  /** `http` is the one seam a test substitutes to answer every resource
   *  client at once; production always builds the real one. */
  constructor(apiBaseUrl: string, http: ApiHttp = new ApiHttp(apiBaseUrl)) {
    this.base = apiBaseUrl;
    this.http = http;
    this.hosts = new HostsApi(this.http);
    this.containers = new ContainersApi(this.http);
    this.agents = new AgentsApi(this.http);
    this.mcp = new McpCatalogApi(this.http);
    this.prompts = new PromptsApi(this.http);
    this.providers = new ProvidersApi(this.http);
    this.templates = new TemplatesApi(this.http);
    this.board = new BoardApi(this.http);
    this.server = new ServerApi(this.http);
  }

  health(): Promise<{ status: string; version: string; dockerConnected: boolean }> {
    return this.http.get('/health');
  }

  /** Where a shell in this container connects. Not a request — a URL for the caller to open a
   *  WebSocket on, since a socket is held open by whoever owns the terminal, not by a client
   *  that returns a promise. */
  terminalSocketUrl(hostId: string, containerId: string): string {
    return terminalSocketUrl(this.base, hostId, containerId);
  }
}
