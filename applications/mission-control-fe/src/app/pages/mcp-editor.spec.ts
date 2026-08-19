import { describe, expect, it } from 'vitest';
import { McpCatalogServer } from '../core/models';
import {
  McpEditorDraft, applyMcpKindDefaults, mcpDraftFromServer, mcpDraftToInput, mcpDraftValid,
  newMcpDraft, splitMcpLines,
} from './mcp-editor';

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

});

const storedServer = (patch: Partial<McpCatalogServer> = {}): McpCatalogServer => ({
  id: 'mcp-1', name: 'Example', description: 'server', kind: 'managed', hostId: 'dh-local',
  transport: 'http', url: null, image: 'image:latest', platform: null,
  entrypoint: ['node'], command: ['server.js'], stdioCommand: null, args: [],
  internalPort: 1100, publishedPort: null, path: '/mcp', crossHostUrl: null,
  connectionUrl: 'http://example:1100/mcp',
  headers: [], environment: [], volumes: [], healthcheck: null, supportServices: [],
  desiredState: 'stopped', runtimeState: 'stopped', operationState: 'idle',
  operationError: null, checkStatus: 'unknown', checkError: null, checkedAt: null,
  latencyMs: null, revision: 3, appliedRevision: 3, pendingChanges: false,
  serviceKey: 'example', createdAt: 1, updatedAt: 2,
  ...patch,
});

describe('MCP Servers draft loading', () => {
  it('edits in place, keeping the id and the host the stack already runs on', () => {
    const loaded = mcpDraftFromServer(storedServer());

    expect(loaded).toMatchObject({ id: 'mcp-1', hostLocked: true, name: 'Example' });
    expect(loaded.entrypoint).toBe('node');
  });

  it('duplicates as a new entry and forgets which secrets were stored', () => {
    const loaded = mcpDraftFromServer(storedServer({
      environment: [{ key: 'TOKEN', value: null, secret: true, set: true, recoverable: true }],
    }), true);

    expect(loaded).toMatchObject({ id: null, hostLocked: false, name: 'Example copy' });
    expect(loaded.environment[0]).toMatchObject({ value: '', set: false, recoverable: false });
  });

  it('copies nested structures so cancelling an edit cannot mutate the catalog', () => {
    const server = storedServer({
      volumes: [{ name: 'data', target: '/data' }],
      healthcheck: { test: ['CMD', 'true'], interval: '30s', timeout: '5s', retries: 3 },
    });
    const loaded = mcpDraftFromServer(server);
    loaded.volumes[0].name = 'edited';
    loaded.healthcheck!.test.push('extra');

    expect(server.volumes[0].name).toBe('data');
    expect(server.healthcheck!.test).toEqual(['CMD', 'true']);
  });
});

describe('MCP Servers draft validation', () => {
  const valid = (patch: Partial<McpEditorDraft> = {}, existing: McpCatalogServer[] = []) =>
    mcpDraftValid(draft(patch), existing);

  it('accepts a complete managed draft', () => {
    expect(valid()).toBe(true);
  });

  it('refuses a name another catalog entry already answers to', () => {
    expect(valid({}, [storedServer({ id: 'other', name: 'example' })])).toBe(false);
    // the entry being edited is not its own duplicate
    expect(valid({ id: 'mcp-1' }, [storedServer({ id: 'mcp-1', name: 'Example' })])).toBe(true);
  });

  it('requires a host, an image and a port in range for a managed server', () => {
    expect(valid({ hostId: '' })).toBe(false);
    expect(valid({ image: '  ' })).toBe(false);
    expect(valid({ internalPort: 0 })).toBe(false);
    expect(valid({ internalPort: 70_000 })).toBe(false);
    expect(valid({ publishedPort: 65_536 })).toBe(false);
  });

  it('requires an absolute single-slash path with no fragment', () => {
    expect(valid({ path: 'mcp' })).toBe(false);
    expect(valid({ path: '//mcp' })).toBe(false);
    expect(valid({ path: '/mcp#x' })).toBe(false);
  });

  it('rejects a volume that could escape its mount or shadow the daemon socket', () => {
    expect(valid({ volumes: [{ name: 'data', target: '/var/run/docker.sock' }] })).toBe(false);
    expect(valid({ volumes: [{ name: 'data', target: '/data/../etc' }] })).toBe(false);
    expect(valid({ volumes: [{ name: 'Data', target: '/data' }] })).toBe(false);
    expect(valid({ volumes: [{ name: 'data', target: 'data' }] })).toBe(false);
  });

  it('refuses duplicate keys, illegal keys, and a secret with nothing behind it', () => {
    const entry = (key: string, patch = {}) =>
      ({ key, value: 'v', secret: false, set: false, recoverable: true, ...patch });
    expect(valid({ environment: [entry('A'), entry('A')] })).toBe(false);
    expect(valid({ environment: [entry('1BAD')] })).toBe(false);
    expect(valid({ environment: [entry('TOKEN', { secret: true, value: '' })] })).toBe(false);
    // a stored secret needs no re-typed value
    expect(valid({ environment: [entry('TOKEN', { secret: true, value: '', set: true })] })).toBe(true);
    // header names are case-insensitive, so these two collide
    expect(valid({ headers: [entry('X-Key'), entry('x-key')] })).toBe(false);
  });

  it('validates healthcheck verbs, durations and retry bounds', () => {
    expect(valid({ healthcheck: { test: ['SHELL'], retries: 3 } })).toBe(false);
    expect(valid({ healthcheck: { test: ['NONE', 'true'], retries: 3 } })).toBe(false);
    expect(valid({ healthcheck: { test: ['CMD', 'true'], interval: '30 seconds' } })).toBe(false);
    expect(valid({ healthcheck: { test: ['CMD', 'true'], retries: 0 } })).toBe(false);
    expect(valid({ healthcheck: { test: ['CMD', 'true'], interval: '1500ms', retries: 5 } })).toBe(true);
  });

  it('requires each support service to be named like a Compose service, once', () => {
    const service = (name: string) => ({
      name, image: 'postgres:16', platform: null, entrypoint: [], command: [],
      environment: [], volumes: [], healthcheck: null,
    });
    expect(valid({ supportServices: [service('database')] })).toBe(true);
    expect(valid({ supportServices: [service('Database')] })).toBe(false);
    expect(valid({ supportServices: [service('database'), service('database')] })).toBe(false);
    expect(valid({ supportServices: [{ ...service('database'), image: '' }] })).toBe(false);
  });

  it('holds each other kind to its own single requirement', () => {
    expect(valid({ kind: 'external', url: 'https://mcp.example.test/mcp' })).toBe(true);
    expect(valid({ kind: 'external', url: 'ftp://mcp.example.test' })).toBe(false);
    expect(valid({ kind: 'stdio', stdioCommand: 'npx' })).toBe(true);
    expect(valid({ kind: 'stdio', stdioCommand: ' ' })).toBe(false);
  });
});

describe('MCP Servers editor drafts', () => {
  it('starts a managed entry on the defaults the backend expects', () => {
    expect(newMcpDraft('managed', 'dh-local')).toMatchObject({
      kind: 'managed', hostId: 'dh-local', transport: 'http', internalPort: 1100, path: '/mcp',
    });
  });

  it('starts a stdio entry with no port to publish', () => {
    expect(newMcpDraft('stdio', 'dh-local')).toMatchObject({
      transport: 'stdio', internalPort: null,
    });
  });

  it('re-applies the defaults a kind switch decides, in place', () => {
    const d = draft({ kind: 'stdio', transport: 'http', internalPort: null, path: '' });

    applyMcpKindDefaults(d);
    expect(d).toMatchObject({ transport: 'stdio', internalPort: null, path: '' });

    d.kind = 'managed';
    applyMcpKindDefaults(d);
    expect(d).toMatchObject({ transport: 'http', internalPort: 1100, path: '/mcp' });
  });

  it('leaves a managed entry\'s own port and path alone', () => {
    const d = draft({ kind: 'managed', internalPort: 9000, path: '/rpc' });

    applyMcpKindDefaults(d);

    expect(d).toMatchObject({ internalPort: 9000, path: '/rpc' });
  });

  it('copies a support service\'s own environment, volumes and healthcheck', () => {
    const server = {
      ...(mcpDraftToInput(draft()) as unknown as McpCatalogServer),
      id: 'mcp-1', hostId: 'dh-local', createdAt: 1, updatedAt: 1,
      volumes: [{ name: 'data', target: '/data' }],
      supportServices: [{
        name: 'database', image: 'postgres:16', platform: null, entrypoint: [], command: [],
        environment: [{ key: 'PASSWORD', value: 'p', secret: true, set: true, recoverable: true }],
        volumes: [{ name: 'db', target: '/var/lib/postgresql' }],
        healthcheck: { test: ['CMD', 'pg_isready'], interval: '30s', retries: 3 },
      }],
    } as unknown as McpCatalogServer;

    const loaded = mcpDraftFromServer(server);

    expect(loaded.supportServices[0]).toMatchObject({
      name: 'database',
      volumes: [{ name: 'db', target: '/var/lib/postgresql' }],
      healthcheck: { test: ['CMD', 'pg_isready'], retries: 3 },
    });
    // a stored secret is never re-read into the form, only marked as set
    expect(loaded.supportServices[0].environment[0]).toMatchObject({ value: '', set: true });
  });

  it('strips the stored secrets a duplicate cannot inherit', () => {
    const server = {
      ...(mcpDraftToInput(draft()) as unknown as McpCatalogServer),
      id: 'mcp-1', hostId: 'dh-local', createdAt: 1, updatedAt: 1,
      environment: [{ key: 'TOKEN', value: 't', secret: true, set: true, recoverable: true }],
      supportServices: [],
    } as unknown as McpCatalogServer;

    expect(mcpDraftFromServer(server).environment[0]).toMatchObject({ set: true });
    expect(mcpDraftFromServer(server, true).environment[0])
      .toMatchObject({ set: false, recoverable: false, value: '' });
  });

  it('rejects a support service whose own healthcheck is malformed', () => {
    const service = {
      name: 'database', image: 'postgres:16', platform: null, entrypoint: [], command: [],
      environment: [], volumes: [],
      healthcheck: { test: ['SHELL'], retries: 3 },
    };

    expect(mcpDraftValid(draft({ supportServices: [service] as never }), [])).toBe(false);
  });
});
