import { describe, expect, it } from 'vitest';
import { ApiAgentProfile, ApiImageTags, ApiMcpCatalogServer, ApiProfileTemplate } from '../hermes-api';
import {
  toAgentProfile, toImageCatalog, toMcpCatalogServer, toMcpRetainedResource, toProfileTemplate,
} from './wire-mappers';

/**
 * These mappers exist because the wire is looser than the model: a field the
 * current backend always sends may be missing from a row an older one wrote, and
 * both have to render. So each case here is a payload that is legal on the wire
 * and would otherwise reach a template as undefined.
 */
describe('toAgentProfile', () => {
  const minimal = {
    id: 'a-1', containerId: 'c-1', name: 'atlas', role: 'Ops', state: 'idle',
    provider: 'anthropic', model: 'm', cwd: '/opt/data', soul: '', memoryMd: '',
    configYaml: '', lastActive: 20,
  } as ApiAgentProfile;

  it('fills in the lists a sparse row leaves out', () => {
    const profile = toAgentProfile(minimal);

    expect(profile).toMatchObject({
      apiKeyMasked: '', skills: [], mcp: [], integrations: [], sessions: [],
      msgsToday: 0, tokensToday: 0, errorRate: 0,
    });
  });

  it('treats an MCP server as enabled unless it says otherwise', () => {
    const enabled = toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0];
    const disabled = toAgentProfile({ ...minimal, mcp: [server({ enabled: false })] }).mcp[0];

    expect(enabled.enabled).toBe(true);
    expect(disabled.enabled).toBe(false);
  });

  it('treats anything but an explicit catalog origin as a directly-edited server', () => {
    expect(toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0].origin).toBe('custom');
    expect(toAgentProfile({ ...minimal, mcp: [server({ origin: 'catalog' })] }).mcp[0].origin)
      .toBe('catalog');
  });

  it('nulls the catalog bookkeeping a pre-catalog row has none of', () => {
    expect(toAgentProfile({ ...minimal, mcp: [server({})] }).mcp[0]).toMatchObject({
      catalogServerId: null, syncedRevision: null, catalogRevision: null,
      updateAvailable: false, error: null, checkedAt: null,
      url: undefined, command: undefined, args: undefined,
    });
  });

  it('keeps the endpoint fields a server does carry', () => {
    const mcp = toAgentProfile({
      ...minimal,
      mcp: [server({ url: 'http://gh', command: 'npx', args: '-y pkg', catalogServerId: 'mcp-1' })],
    }).mcp[0];

    expect(mcp).toMatchObject({
      url: 'http://gh', command: 'npx', args: '-y pkg', catalogServerId: 'mcp-1',
    });
  });

  it('reads a skill\'s enabled flag as a boolean, whatever the row holds', () => {
    const skills = toAgentProfile({
      ...minimal,
      skills: [
        { id: 's1', name: 'ops', source: 'bundled', version: '1', description: '', enabled: true },
        { id: 's2', name: 'research', source: 'user', version: '1', description: '' },
      ] as never,
    }).skills;

    expect(skills.map(s => s.enabled)).toEqual([true, false]);
  });

  function server(patch: Record<string, unknown>) {
    return {
      id: 'm-1', name: 'github', transport: 'http', status: 'connected', tools: 2,
      latencyMs: 30, ...patch,
    } as never;
  }
});

describe('toMcpCatalogServer', () => {
  const minimal: ApiMcpCatalogServer = { id: 'mcp-1', desiredState: 'stopped' };

  it('fills in every list and label a sparse row leaves out', () => {
    expect(toMcpCatalogServer(minimal)).toMatchObject({
      name: '', description: '', kind: 'external', hostId: null, transport: 'http',
      url: null, image: null, entrypoint: [], command: [], stdioCommand: null, args: [],
      internalPort: null, publishedPort: null, path: null, crossHostUrl: null,
      connectionUrl: null, headers: [], environment: [], volumes: [], healthcheck: null,
      supportServices: [], operationState: 'idle', operationError: null, checkError: null,
      checkedAt: null, latencyMs: null, revision: 1, appliedRevision: 0,
      pendingChanges: false, serviceKey: null,
    });
  });

  it('defaults a stdio entry to the stdio transport', () => {
    expect(toMcpCatalogServer({ ...minimal, kind: 'stdio' }).transport).toBe('stdio');
  });

  it('normalizes the states the backend spells in mixed case', () => {
    const server = toMcpCatalogServer({
      ...minimal, desiredState: 'RUNNING', runtimeState: 'Running',
      checkStatus: 'CONNECTED', operationState: 'Starting',
    } as never);

    expect(server).toMatchObject({
      desiredState: 'running', runtimeState: 'running',
      checkStatus: 'connected', operationState: 'starting',
    });
  });

  it('falls back to unknown for a state it does not recognise', () => {
    const server = toMcpCatalogServer({
      ...minimal, runtimeState: 'reticulating', checkStatus: 'perhaps',
    } as never);

    expect(server).toMatchObject({ runtimeState: 'unknown', checkStatus: 'unknown' });
  });

  it('reads a config entry\'s secret flag as a boolean', () => {
    const server = toMcpCatalogServer({
      ...minimal,
      headers: [{ key: 'Authorization', value: 't' }],
      environment: [{ key: 'TOKEN', value: 't', secret: true }],
    } as never);

    expect(server.headers[0].secret).toBe(false);
    expect(server.environment[0].secret).toBe(true);
  });

  it('reads a support service\'s own entries the same way, not just the top-level ones', () => {
    const service = toMcpCatalogServer({
      ...minimal,
      supportServices: [{
        name: 'database', image: 'postgres:16',
        environment: [{ key: 'POSTGRES_PASSWORD', value: null }],
      }],
    }).supportServices[0];

    expect(service.environment?.[0]).toMatchObject({ secret: false, value: null });
    // absent lists stay absent — the editor sends this shape back, and an empty
    // entrypoint would clear the image's own instead of leaving it alone
    expect(service).toMatchObject({ platform: null });
    expect(service.entrypoint).toBeUndefined();
    expect(service.command).toBeUndefined();
    expect(service.volumes).toBeUndefined();
  });

  it('falls back to an external HTTP entry for a kind this build does not know', () => {
    const server = toMcpCatalogServer({ ...minimal, kind: 'quantum' } as never);

    expect(server).toMatchObject({ kind: 'external', transport: 'http' });
  });

  it('falls back to the kind\'s own transport when the row names an unknown one', () => {
    expect(toMcpCatalogServer({ ...minimal, kind: 'stdio', transport: 'carrier-pigeon' } as never)
      .transport).toBe('stdio');
  });

  it('leaves a field this build knows nothing about out of the store', () => {
    const server = toMcpCatalogServer({ ...minimal, quantumEntanglement: true } as never);

    expect(server).not.toHaveProperty('quantumEntanglement');
  });

  it('copies nested structures, so the store cannot alias the response body', () => {
    const payload = {
      ...minimal,
      volumes: [{ name: 'data', target: '/data' }],
      healthcheck: { test: ['CMD', 'true'] },
    };
    const server = toMcpCatalogServer(payload as never);
    server.volumes[0].name = 'edited';
    server.healthcheck!.test.push('extra');

    expect(payload.volumes[0].name).toBe('data');
    expect(payload.healthcheck.test).toEqual(['CMD', 'true']);
  });
});

describe('toMcpRetainedResource', () => {
  it('gives every label the row is rendered by something to show', () => {
    expect(toMcpRetainedResource({ id: 'vol-1' })).toEqual({
      id: 'vol-1', serverId: null, serverName: '', hostId: '', type: 'volume', name: '',
      createdAt: 0,
    });
  });

  it('keeps what the row does carry', () => {
    expect(toMcpRetainedResource({
      id: 'vol-1', serverId: 'mcp-1', serverName: 'browser', hostId: 'dh-local',
      type: 'volume', name: 'browser-data', createdAt: 7,
    })).toMatchObject({ serverId: 'mcp-1', serverName: 'browser', name: 'browser-data' });
  });
});

describe('toImageCatalog', () => {
  it('treats every tag as pulled on a backend that reports no local presence', () => {
    const catalog = toImageCatalog({
      repository: 'nousresearch/hermes-agent', tags: ['v1', 'v2'],
    } as ApiImageTags);

    expect(catalog.tags).toEqual([
      { tag: 'v1', pulled: true, digest: null }, { tag: 'v2', pulled: true, digest: null }]);
    expect(catalog.registryStatus).toBe('unavailable');
  });

  it('takes the entries over the tag list once the backend sends them', () => {
    const catalog = toImageCatalog({
      repository: 'r', tags: ['ignored'], registryStatus: 'ok',
      entries: [{ tag: 'v2', pulled: true, remote: true }, { tag: 'v3', pulled: false, remote: true }],
    } as ApiImageTags);

    expect(catalog.tags).toEqual([
      { tag: 'v2', pulled: true, digest: null }, { tag: 'v3', pulled: false, digest: null }]);
    expect(catalog.registryStatus).toBe('ok');
  });
});

describe('toProfileTemplate', () => {
  const minimal = { id: 'pt-1', name: 'ops', createdAt: 1, updatedAt: 2 } as ApiProfileTemplate;

  it('fills in every field a template row can omit', () => {
    expect(toProfileTemplate(minimal)).toEqual({
      id: 'pt-1', name: 'ops', description: '', provider: '', model: '', baseUrl: '',
      cwd: '', soul: '', memory: '', skills: [], mcpServers: [], secrets: [],
      createdAt: 1, updatedAt: 2,
    });
  });

  it('treats a template MCP server as enabled unless it says otherwise', () => {
    const servers = toProfileTemplate({
      ...minimal,
      mcpServers: [
        { name: 'files', transport: 'stdio', command: 'npx' },
        { name: 'gh', transport: 'http', url: 'http://gh', enabled: false },
      ] as never,
    }).mcpServers;

    expect(servers.map(m => m.enabled)).toEqual([true, false]);
  });

  it('reads each key\'s flags as booleans, so an unset key never reads as set', () => {
    const secrets = toProfileTemplate({
      ...minimal,
      secrets: [{ key: 'A' }, { key: 'B', set: true, recoverable: true }] as never,
    }).secrets;

    expect(secrets).toEqual([
      { key: 'A', set: false, recoverable: false },
      { key: 'B', set: true, recoverable: true },
    ]);
  });
});
