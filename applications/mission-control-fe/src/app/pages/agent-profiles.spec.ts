import '@angular/compiler';
import { describe, expect, it } from 'vitest';
import { McpCatalogServer } from '../core/models';
import { catalogTemplateSnapshot, detachedTemplateMcp } from './agent-profiles';

const server = (patch: Partial<McpCatalogServer>): McpCatalogServer => ({
  id: 'mcp-1', name: 'Tools', description: '', kind: 'external', hostId: null,
  transport: 'http', url: 'https://tools.example.test/mcp', image: null, platform: null,
  entrypoint: [], command: [], stdioCommand: null, args: [], internalPort: null,
  publishedPort: null, path: null, crossHostUrl: null, connectionUrl: null,
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'stopped', runtimeState: 'unknown', operationState: 'idle',
  operationError: null, checkStatus: 'unknown', checkError: null, checkedAt: null,
  latencyMs: null, revision: 1, appliedRevision: 0, pendingChanges: false,
  serviceKey: null, createdAt: 1, updatedAt: 1,
  ...patch,
});

describe('Agent Profile MCP catalog snapshots', () => {
  it('sends only a transient source id with the connection preview', () => {
    expect(catalogTemplateSnapshot(server({}), 'research-tools')).toEqual({
      name: 'research-tools', transport: 'http',
      url: 'https://tools.example.test/mcp', enabled: true, sourceServerId: 'mcp-1',
    });
  });

  it('prefers a managed cross-host URL over stack-internal DNS', () => {
    const snapshot = catalogTemplateSnapshot(server({
      kind: 'managed', hostId: 'dh-local',
      connectionUrl: 'http://tools:1100/mcp',
      crossHostUrl: 'https://mcp.example.test/mcp',
    }), 'tools');

    expect(snapshot?.url).toBe('https://mcp.example.test/mcp');
  });

  it('preserves stdio argument boundaries in the preview', () => {
    const snapshot = catalogTemplateSnapshot(server({
      kind: 'stdio', transport: 'stdio', stdioCommand: 'npx',
      args: ['-y', '@acme/server', 'two words'], url: null,
    }), 'local-tools');

    expect(snapshot).toMatchObject({
      command: 'npx', args: "-y @acme/server 'two words'", sourceServerId: 'mcp-1',
    });
  });

  it('drops the transient source id after the first successful save', () => {
    const pending = catalogTemplateSnapshot(server({}), 'tools')!;

    expect(detachedTemplateMcp(pending)).not.toHaveProperty('sourceServerId');
  });
});
