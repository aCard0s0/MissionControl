import { DockerHost, McpCatalogServer } from '../core/models';

// How the MCP Servers page arranges the flat catalog for display, minus the UI:
// a pure function, so the grouping rules are testable without rendering a page.

/** One rendered section of the catalog. */
export interface McpServerSection {
  key: string;
  label: string;
  detail: string;
  /** The Docker Compose project a managed stack runs in; absent for the other sections. */
  project?: string;
  servers: McpCatalogServer[];
}

/** Mirrors `ManagedMcpStack.PROJECT` on the backend — the project `docker compose ls` shows. */
export const MANAGED_PROJECT = 'mission-control-mcp';

/** The bucket a managed server with no host lands in. */
const UNASSIGNED = 'unassigned';

/**
 * Groups the catalog the way the page reads it: managed servers per Docker host
 * first — because that is the stack an operator starts and stops — then external
 * endpoints and reusable stdio definitions, each shown only when it has members.
 *
 * Host order follows first appearance in `servers`, so a poll that returns the
 * catalog in a stable order keeps the sections from reshuffling under the cursor.
 * A managed server naming a host Mission Control no longer knows is still shown,
 * labelled by its raw id rather than dropped.
 */
export function mcpServerSections(
  servers: readonly McpCatalogServer[],
  hosts: readonly DockerHost[],
): McpServerSection[] {
  const managed = servers.filter(server => server.kind === 'managed');
  const external = servers.filter(server => server.kind === 'external');
  const stdio = servers.filter(server => server.kind === 'stdio');
  const hostIds = Array.from(new Set(managed.map(server => server.hostId ?? UNASSIGNED)));

  return [
    ...hostIds.map(hostId => {
      const host = hosts.find(item => item.id === hostId);
      return {
        key: `managed-${hostId}`,
        label: `Managed stack — ${host?.name ?? (hostId === UNASSIGNED ? 'Unassigned' : hostId)}`,
        detail: host?.url || 'Docker host unavailable',
        project: MANAGED_PROJECT,
        servers: managed.filter(server => (server.hostId ?? UNASSIGNED) === hostId),
      };
    }),
    ...(external.length ? [{
      key: 'external',
      label: 'External endpoints',
      detail: 'Local or remote HTTP/SSE MCP servers',
      servers: external,
    }] : []),
    ...(stdio.length ? [{
      key: 'stdio',
      label: 'Reusable stdio definitions',
      detail: 'Commands materialized inside an Agent container',
      servers: stdio,
    }] : []),
  ];
}
