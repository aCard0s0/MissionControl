import { describe, expect, it } from 'vitest';
import { mcpServerSections } from './mcp-server-sections';
import { catalogServer as server, dockerHost } from '../testing/models';

const hosts = [
  dockerHost('dh-local', { name: 'localhost', url: 'unix:///var/run/docker.sock' }),
  dockerHost('dh-edge', { name: 'edge', url: 'tcp://edge:2375' }),
];

describe('MCP server grouping', () => {
  it('gives each Docker host its own managed section, named after the host', () => {
    const groups = mcpServerSections(
      [server('a'), server('b', { hostId: 'dh-edge' }), server('c')], hosts);

    expect(groups.map(g => g.key)).toEqual(['managed-dh-local', 'managed-dh-edge']);
    expect(groups[0]).toMatchObject({
      label: 'Managed stack — localhost', detail: 'unix:///var/run/docker.sock',
    });
    expect(groups[0].servers.map(s => s.id)).toEqual(['a', 'c']);
    expect(groups[1].servers.map(s => s.id)).toEqual(['b']);
  });

  it('orders hosts by first appearance, so a poll cannot reshuffle the sections', () => {
    const groups = mcpServerSections([server('a', { hostId: 'dh-edge' }), server('b')], hosts);

    expect(groups.map(g => g.key)).toEqual(['managed-dh-edge', 'managed-dh-local']);
  });

  it('still shows a server whose host Mission Control no longer knows', () => {
    const groups = mcpServerSections([server('a', { hostId: 'dh-gone' })], hosts);

    expect(groups[0]).toMatchObject({
      key: 'managed-dh-gone', label: 'Managed stack — dh-gone',
      detail: 'Docker host unavailable',
    });
  });

  it('collects managed servers with no host at all under Unassigned', () => {
    const groups = mcpServerSections([server('a', { hostId: null })], hosts);

    expect(groups[0]).toMatchObject({
      key: 'managed-unassigned', label: 'Managed stack — Unassigned',
    });
  });

  it('shows the external and stdio sections only once each has a member', () => {
    expect(mcpServerSections([server('a')], hosts).map(g => g.key)).toEqual(['managed-dh-local']);

    const all = mcpServerSections([
      server('a'),
      server('b', { kind: 'external', hostId: null, url: 'https://mcp.example.test/mcp' }),
      server('c', { kind: 'stdio', hostId: null, stdioCommand: 'npx' }),
    ], hosts);

    expect(all.map(g => g.key)).toEqual(['managed-dh-local', 'external', 'stdio']);
    expect(all[1].servers.map(s => s.id)).toEqual(['b']);
    expect(all[2].servers.map(s => s.id)).toEqual(['c']);
  });

  it('answers with nothing at all for an empty catalog', () => {
    expect(mcpServerSections([], hosts)).toEqual([]);
  });
});
