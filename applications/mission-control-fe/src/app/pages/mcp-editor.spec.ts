import { describe, expect, it } from 'vitest';
import {
  McpEditorDraft, httpEndpointValid, mcpDraftToInput, mcpOperationActive, splitMcpLines,
} from './mcp-servers';

const draft = (patch: Partial<McpEditorDraft> = {}): McpEditorDraft => ({
  id: null, hostLocked: false, name: ' Example ', description: ' server ', kind: 'managed',
  hostId: 'dh-local', transport: 'http', url: '', image: ' image:latest ', platform: '',
  entrypoint: ' node \n\n', command: 'server.js\n--port\n1100', stdioCommand: '', args: '',
  internalPort: 1100, publishedPort: null, path: '/mcp', crossHostUrl: '',
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  ...patch,
});

describe('MCP Servers editor', () => {
  it('keeps list-form commands without invoking a shell parser', () => {
    expect(splitMcpLines(' node\n--message=hello world\n\n1100 '))
      .toEqual(['node', '--message=hello world', '1100']);
  });

  it('only accepts HTTP(S) endpoints', () => {
    expect(httpEndpointValid('https://mcp.example.test/mcp')).toBe(true);
    expect(httpEndpointValid('http://127.0.0.1:1100/sse')).toBe(true);
    expect(httpEndpointValid('file:///etc/passwd')).toBe(false);
    expect(httpEndpointValid('javascript:alert(1)')).toBe(false);
    expect(httpEndpointValid('https://user:secret@mcp.example.test/mcp')).toBe(false);
    expect(httpEndpointValid('https://mcp.example.test/mcp#fragment')).toBe(false);
  });

  it('builds a managed structured request and strips response-only secret flags', () => {
    const input = mcpDraftToInput(draft({
      environment: [{ key: ' TOKEN ', value: '', secret: true, set: true, recoverable: true }],
      volumes: [{ name: ' data ', target: ' /data ' }],
    }));

    expect(input).toMatchObject({
      name: 'Example', kind: 'managed', hostId: 'dh-local', image: 'image:latest',
      entrypoint: ['node'], command: ['server.js', '--port', '1100'],
      environment: [{ key: 'TOKEN', value: '', secret: true }],
      volumes: [{ name: 'data', target: '/data' }],
      stdioCommand: null, args: [], url: null,
    });
    expect(input.environment[0]).not.toHaveProperty('set');
    expect(input.environment[0]).not.toHaveProperty('recoverable');
  });

  it('maps stdio executable and arguments while removing container fields', () => {
    const input = mcpDraftToInput(draft({
      kind: 'stdio', transport: 'stdio', stdioCommand: 'npx', args: '-y\n@acme/server',
      environment: [{ key: 'MODE', value: 'safe', secret: false }],
    }));

    expect(input).toMatchObject({
      kind: 'stdio', transport: 'stdio', hostId: null, image: null,
      stdioCommand: 'npx', args: ['-y', '@acme/server'],
      environment: [{ key: 'MODE', value: 'safe', secret: false }],
      headers: [], volumes: [],
    });
  });

  it('round-trips safe support services without exposing stored secrets', () => {
    const input = mcpDraftToInput(draft({
      supportServices: [{
        name: 'database', image: 'postgres:16-alpine', command: ['postgres'],
        environment: [
          { key: 'POSTGRES_PASSWORD', value: '', secret: true, set: true },
          { key: 'NEW_TOKEN', value: 'new-secret', secret: true, set: false },
        ],
        volumes: [{ name: 'data', target: '/var/lib/postgresql/data' }],
        healthcheck: { test: ['CMD', 'pg_isready'], interval: '5s', timeout: '3s', retries: 20 },
      }],
    }));

    expect(input.supportServices[0]).toMatchObject({
      name: 'database', image: 'postgres:16-alpine', command: ['postgres'],
      environment: [
        { key: 'POSTGRES_PASSWORD', value: '', secret: true },
        { key: 'NEW_TOKEN', value: 'new-secret', secret: true },
      ],
      volumes: [{ name: 'data', target: '/var/lib/postgresql/data' }],
      healthcheck: { test: ['CMD', 'pg_isready'], interval: '5s', retries: 20 },
    });
    expect(input.supportServices[0].environment?.[0]).not.toHaveProperty('set');
  });

  it('recognizes only non-terminal lifecycle states as active', () => {
    expect(mcpOperationActive('pulling')).toBe(true);
    expect(mcpOperationActive('idle')).toBe(false);
    expect(mcpOperationActive('error')).toBe(false);
  });
});
