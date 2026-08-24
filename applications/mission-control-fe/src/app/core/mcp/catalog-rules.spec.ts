import { describe, expect, it } from 'vitest';
import { McpCatalogServer } from '../models';
import {
  IN_FLIGHT_STATE, duplicateCatalogName, httpEndpointValid, mcpConfigEntriesValid, mcpDisplayEndpoint,
  mcpHealthcheckValid, mcpOperationActive, mcpPathValid, mcpPortValid,
  mcpSupportServiceNameValid, mcpVolumeValid,
} from './catalog-rules';
import { catalogServer as server } from '../../testing/models';

describe('mcpOperationActive', () => {
  it('treats every state the backend runs an operation in as active', () => {
    for (const state of
      ['provisioning', 'reconciling', 'starting', 'stopping', 'applying', 'deleting']) {
      expect(mcpOperationActive(state)).toBe(true);
    }
  });

  it('treats only idle and error as settled, in whatever case they arrive', () => {
    // error is settled on purpose: a failure the operator can act on is not a run still going
    for (const state of ['idle', 'error', 'IDLE', 'Error']) {
      expect(mcpOperationActive(state)).toBe(false);
    }
  });

  it('counts a state this build does not know as active, so the controls stay locked', () => {
    // the backend names the states; guessing that a new one is settled would hand an entry
    // back to the operator in the middle of an operation
    for (const state of ['', 'pulling', 'migrating']) {
      expect(mcpOperationActive(state)).toBe(true);
    }
  });

  it('reports the states the store patches in optimistically as active', () => {
    for (const state of Object.values(IN_FLIGHT_STATE)) {
      expect(mcpOperationActive(state)).toBe(true);
    }
  });
});

describe('duplicateCatalogName', () => {
  const catalog = [server('browser'), server('files')];

  it('answers with the entry already holding the name, whatever its case or padding', () => {
    expect(duplicateCatalogName('BROWSER', catalog)?.id).toBe('browser');
    expect(duplicateCatalogName('  browser  ', catalog)?.id).toBe('browser');
    expect(duplicateCatalogName('shell', catalog)).toBeNull();
  });

  it('does not count the entry being edited as its own duplicate', () => {
    expect(duplicateCatalogName('browser', catalog, 'browser')).toBeNull();
    expect(duplicateCatalogName('browser', catalog, 'files')?.id).toBe('browser');
  });

  it('leaves a blank name to the required-field check', () => {
    expect(duplicateCatalogName('   ', catalog)).toBeNull();
  });
});

describe('endpoint and port rules', () => {
  it('only accepts HTTP(S) endpoints carrying no credentials or fragment', () => {
    expect(httpEndpointValid('https://mcp.example.test/mcp')).toBe(true);
    expect(httpEndpointValid('http://127.0.0.1:1100/sse')).toBe(true);
    expect(httpEndpointValid('file:///etc/passwd')).toBe(false);
    expect(httpEndpointValid('javascript:alert(1)')).toBe(false);
    expect(httpEndpointValid('ws://mcp.example.test')).toBe(false);
    expect(httpEndpointValid('https://user:secret@mcp.example.test/mcp')).toBe(false);
    expect(httpEndpointValid('https://mcp.example.test/mcp#fragment')).toBe(false);
    expect(httpEndpointValid('not a url')).toBe(false);
  });

  it('holds a port to the TCP range', () => {
    expect(mcpPortValid(1100)).toBe(true);
    expect(mcpPortValid(65_535)).toBe(true);
    expect(mcpPortValid(0)).toBe(false);
    expect(mcpPortValid(70_000)).toBe(false);
    expect(mcpPortValid(null)).toBe(false);
  });

  it('requires an absolute single-slash path with no fragment', () => {
    expect(mcpPathValid('/mcp')).toBe(true);
    expect(mcpPathValid('mcp')).toBe(false);
    expect(mcpPathValid('//mcp')).toBe(false);
    expect(mcpPathValid('/mcp#x')).toBe(false);
  });
});

describe('volume, healthcheck and service-name rules', () => {
  it('rejects a volume that could escape its mount or shadow the daemon socket', () => {
    expect(mcpVolumeValid({ name: 'data', target: '/data' })).toBe(true);
    expect(mcpVolumeValid({ name: 'data', target: '/var/run/docker.sock' })).toBe(false);
    expect(mcpVolumeValid({ name: 'data', target: '/data/../etc' })).toBe(false);
    expect(mcpVolumeValid({ name: 'data', target: '/data/..' })).toBe(false);
    expect(mcpVolumeValid({ name: 'Data', target: '/data' })).toBe(false);
    expect(mcpVolumeValid({ name: 'data', target: 'data' })).toBe(false);
  });

  it('validates healthcheck verbs, durations and retry bounds', () => {
    expect(mcpHealthcheckValid(null)).toBe(true);
    expect(mcpHealthcheckValid({ test: ['CMD', 'true'], interval: '1500ms', retries: 5 })).toBe(true);
    expect(mcpHealthcheckValid({ test: ['NONE'] })).toBe(true);
    expect(mcpHealthcheckValid({ test: ['SHELL'], retries: 3 })).toBe(false);
    expect(mcpHealthcheckValid({ test: ['NONE', 'true'], retries: 3 })).toBe(false);
    expect(mcpHealthcheckValid({ test: ['CMD', 'true'], interval: '30 seconds' })).toBe(false);
    expect(mcpHealthcheckValid({ test: ['CMD', 'true'], retries: 0 })).toBe(false);
    expect(mcpHealthcheckValid({ test: ['CMD', 'true'], retries: 101 })).toBe(false);
  });

  it('holds a support service to a Compose service name', () => {
    expect(mcpSupportServiceNameValid('database')).toBe(true);
    expect(mcpSupportServiceNameValid('Database')).toBe(false);
    expect(mcpSupportServiceNameValid('-database')).toBe(false);
    expect(mcpSupportServiceNameValid('')).toBe(false);
  });
});

describe('mcpConfigEntriesValid', () => {
  const entry = (key: string, patch = {}) => ({ key, value: 'v', secret: false, ...patch });

  it('refuses duplicate keys, illegal keys, and a secret with nothing behind it', () => {
    expect(mcpConfigEntriesValid([entry('A'), entry('A')], 'env')).toBe(false);
    expect(mcpConfigEntriesValid([entry('1BAD')], 'env')).toBe(false);
    expect(mcpConfigEntriesValid([entry('TOKEN', { secret: true, value: '' })], 'env')).toBe(false);
    // a stored secret needs no re-typed value
    expect(mcpConfigEntriesValid([entry('TOKEN', { secret: true, value: '', set: true })], 'env'))
      .toBe(true);
  });

  it('collides header names case-insensitively and environment keys exactly', () => {
    expect(mcpConfigEntriesValid([entry('X-Key'), entry('x-key')], 'header')).toBe(false);
    expect(mcpConfigEntriesValid([entry('KEY'), entry('key')], 'env')).toBe(true);
  });
});

describe('mcpDisplayEndpoint', () => {
  /** An entry the backend has not resolved an address for yet. */
  const unresolved = (patch: Partial<McpCatalogServer> = {}) =>
    server('a', { connectionUrl: null, ...patch });

  it('shows a stdio definition as the command it would run', () => {
    expect(mcpDisplayEndpoint(server('a', {
      kind: 'stdio', stdioCommand: 'npx', args: ['-y', '@acme/server'],
    }))).toBe('npx -y @acme/server');
  });

  it('prefers the URL the backend resolved over the fields it was built from', () => {
    expect(mcpDisplayEndpoint(server('a', {
      connectionUrl: 'http://a:1100/mcp', crossHostUrl: 'https://edge.example.test/mcp',
    }))).toBe('http://a:1100/mcp');
    expect(mcpDisplayEndpoint(unresolved({ url: 'https://external.example.test/mcp' })))
      .toBe('https://external.example.test/mcp');
    expect(mcpDisplayEndpoint(unresolved({ crossHostUrl: 'https://edge.example.test/mcp' })))
      .toBe('https://edge.example.test/mcp');
  });

  it('says the endpoint is pending rather than showing a blank address', () => {
    expect(mcpDisplayEndpoint(unresolved())).toBe('endpoint pending');
  });
});
