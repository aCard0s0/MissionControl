import { McpCatalogServerInput } from '../models';
import { ApiLogLine, ApiMcpCatalogServer, ApiMcpRetainedResource } from './api-types';
import { ApiHttp, seg } from './http';

/** A managed server's lifecycle verbs — each is its own backend operation. */
export type McpServerOperation = 'start' | 'stop' | 'apply' | 'check';

/** `/api/mcp-servers` — the global MCP definitions, including the Compose stack
 *  Mission Control manages, plus the volumes a delete leaves behind. */
export class McpCatalogApi {
  constructor(private readonly http: ApiHttp) {}

  list(): Promise<ApiMcpCatalogServer[]> {
    return this.http.get('/api/mcp-servers');
  }

  create(input: McpCatalogServerInput): Promise<ApiMcpCatalogServer> {
    return this.http.post('/api/mcp-servers', input);
  }

  update(id: string, input: McpCatalogServerInput): Promise<ApiMcpCatalogServer> {
    return this.http.put(`/api/mcp-servers/${seg(id)}`, input);
  }

  /** Answers with the entry in its `deleting` state, or nothing if it is gone. */
  remove(id: string): Promise<ApiMcpCatalogServer | undefined> {
    return this.http.delete(`/api/mcp-servers/${seg(id)}`);
  }

  run(id: string, operation: McpServerOperation): Promise<ApiMcpCatalogServer | undefined> {
    return this.http.post(`/api/mcp-servers/${seg(id)}/${operation}`);
  }

  logs(id: string, tail = 100): Promise<ApiLogLine[]> {
    return this.http.get(`/api/mcp-servers/${seg(id)}/logs?tail=${seg(tail)}`);
  }

  retainedResources(): Promise<ApiMcpRetainedResource[]> {
    return this.http.get('/api/mcp-servers/retained-resources');
  }

  purgeRetainedResource(id: string): Promise<void> {
    return this.http.delete(`/api/mcp-servers/retained-resources/${seg(id)}`);
  }
}
