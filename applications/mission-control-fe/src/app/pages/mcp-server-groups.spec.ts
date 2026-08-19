import { describe, expect, it } from 'vitest';
import { McpCatalogServer } from '../core/models';
import { mcpDisplayEndpoint, mcpServerGroups } from './mcp-server-groups';
import { catalogServer as server, dockerHost } from '../testing/models';

const hosts = [
  dockerHost('dh-local', { name: 'localhost', url: 'unix:///var/run/docker.sock' }),
  dockerHost('dh-edge', { name: 'edge', url: 'tcp://edge:2375' }),
];

describe('MCP server grouping', () => {
  it('gives each Docker host its own managed section, named after the host', () => {
    const groups = mcpServerGroups(
      [server('a'), server('b', { hostId: 'dh-edge' }), server('c')], hosts);

    expect(groups.map(g => g.key)).toEqual(['managed-dh-local', 'managed-dh-edge']);
    expect(groups[0]).toMatchObject({
      label: 'Managed stack — localhost', detail: 'unix:///var/run/docker.sock',
    });
    expect(groups[0].servers.map(s => s.id)).toEqual(['a', 'c']);
    expect(groups[1].servers.map(s => s.id)).toEqual(['b']);
  });

  it('orders hosts by first appearance, so a poll cannot reshuffle the sections', () => {
    const groups = mcpServerGroups([server('a', { hostId: 'dh-edge' }), server('b')], hosts);

    expect(groups.map(g => g.key)).toEqual(['managed-dh-edge', 'managed-dh-local']);
  });

  it('still shows a server whose host Mission Control no longer knows', () => {
    const groups = mcpServerGroups([server('a', { hostId: 'dh-gone' })], hosts);

    expect(groups[0]).toMatchObject({
      key: 'managed-dh-gone', label: 'Managed stack — dh-gone',
      detail: 'Docker host unavailable',
    });
  });

  it('collects managed servers with no host at all under Unassigned', () => {
    const groups = mcpServerGroups([server('a', { hostId: null })], hosts);

    expect(groups[0]).toMatchObject({
      key: 'managed-unassigned', label: 'Managed stack — Unassigned',
    });
  });

  it('shows the external and stdio sections only once each has a member', () => {
    expect(mcpServerGroups([server('a')], hosts).map(g => g.key)).toEqual(['managed-dh-local']);

    const all = mcpServerGroups([
      server('a'),
      server('b', { kind: 'external', hostId: null, url: 'https://mcp.example.test/mcp' }),
      server('c', { kind: 'stdio', hostId: null, stdioCommand: 'npx' }),
    ], hosts);

    expect(all.map(g => g.key)).toEqual(['managed-dh-local', 'external', 'stdio']);
    expect(all[1].servers.map(s => s.id)).toEqual(['b']);
    expect(all[2].servers.map(s => s.id)).toEqual(['c']);
  });

  it('answers with nothing at all for an empty catalog', () => {
    expect(mcpServerGroups([], hosts)).toEqual([]);
  });
});

describe('MCP server endpoint display', () => {
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
