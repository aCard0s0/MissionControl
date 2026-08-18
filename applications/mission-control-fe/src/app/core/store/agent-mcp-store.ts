import { McpCatalogServer, McpServer } from '../models';
import { AgentStore } from './agent-store';
import { McpCatalogStore } from './mcp-catalog-store';
import { StoreContext, nid } from './store-context';

/** Transport-specific endpoint fields for a directly-configured server. */
export interface McpEndpointOptions {
  url?: string;
  command?: string;
  args?: string;
}

/**
 * The MCP servers one profile connects to. Two kinds live side by side: servers
 * edited directly on the profile, and aliases linked to a catalog entry — which
 * is why connect/sync/unlink exist alongside plain add/update.
 */
export class AgentMcpStore {
  constructor(
    private readonly ctx: StoreContext,
    private readonly agents: AgentStore,
    private readonly catalog: McpCatalogStore,
  ) {}

  async add(
    agentId: string, name: string, transport: McpServer['transport'], opts?: McpEndpointOptions,
  ): Promise<boolean> {
    if (!this.agents.byId(agentId)) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'mcp add', ref => this.ctx.api.agents.mcp.add(ref, {
        name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
      }));
    }
    // mock upsert: replace a same-named server (edit), else append
    const server = directServer(name, transport, opts);
    this.agents.update(agentId, x => {
      const existing = x.mcp.find(m => m.name === name);
      return {
        ...x,
        mcp: existing
          ? x.mcp.map(m => m.name === name ? { ...server, id: m.id } : m)
          : [...x.mcp, server],
      };
    });
    return true;
  }

  /** Atomic direct-server edit/rename. Catalog-linked servers must be unlinked
   *  by the caller first, which keeps registry synchronization explicit. */
  async update(
    agentId: string, oldName: string, name: string, transport: McpServer['transport'],
    opts?: McpEndpointOptions,
  ): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const existing = agent?.mcp.find(server => server.name === oldName);
    if (!agent || !existing) return false;
    if (oldName !== name && agent.mcp.some(server => server.name === name)) {
      this.ctx.toast(`MCP alias already exists: ${name}`);
      return false;
    }
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'MCP update',
        ref => this.ctx.api.agents.mcp.update(ref, oldName, {
          name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
          enabled: existing.enabled,
        }));
    }
    this.agents.update(agentId, item => ({
      ...item,
      mcp: item.mcp.map(server => server.name !== oldName ? server : ({
        ...server, name, transport, url: opts?.url, command: opts?.command, args: opts?.args,
        status: server.enabled ? 'unknown' as const : 'disabled' as const,
        tools: 0, latencyMs: null, error: null, checkedAt: null,
      })),
    }));
    return true;
  }

  async setEnabled(agentId: string, serverName: string, enabled: boolean): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent?.mcp.some(server => server.name === serverName)) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, `MCP ${enabled ? 'connect' : 'disconnect'}`,
        ref => this.ctx.api.agents.mcp.setEnabled(ref, serverName, enabled));
    }
    this.agents.update(agentId, item => ({
      ...item,
      mcp: item.mcp.map(server => server.name !== serverName ? server : ({
        ...server, enabled, status: enabled ? 'unknown' as const : 'disabled' as const,
        ...(enabled ? { error: null } : {}),
      })),
    }));
    return true;
  }

  /** Starts a stopped managed catalog server and waits for the real runtime
   *  state before writing any Agent configuration. */
  async connectCatalog(agentId: string, serverId: string, alias: string): Promise<boolean> {
    const resolved = this.agents.resolve(agentId);
    const catalog = this.catalog.byId(serverId);
    if (!resolved || !catalog || !alias.trim()) return false;
    const { agent, ref } = resolved;
    if (agent.mcp.some(server => server.name === alias)) {
      this.ctx.toast(`MCP alias already exists: ${alias}`);
      return false;
    }
    if (catalog.kind === 'managed' && catalog.hostId !== ref.hostId && !catalog.crossHostUrl) {
      this.ctx.toast(`MCP server ${catalog.name} needs an explicit cross-host URL for this Agent`);
      return false;
    }
    if (catalog.kind === 'managed' && catalog.runtimeState !== 'running') {
      if (!(await this.catalog.start(serverId))) return false;
      if (!(await this.catalog.waitUntilRunning(serverId))) return false;
    }

    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'MCP catalog connect',
        target => this.ctx.api.agents.mcp.connectCatalog(target, serverId, alias.trim()));
    }
    const linked = catalogDefinition(this.catalog.byId(serverId)!, alias.trim(), true);
    this.agents.update(agentId, item => ({ ...item, mcp: [...item.mcp, linked] }));
    return true;
  }

  /** Re-applies the catalog definition onto an alias already linked to it. */
  async syncCatalog(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const linked = agent?.mcp.find(server => server.name === alias && server.catalogServerId);
    if (!agent || !linked?.catalogServerId) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'MCP sync',
        ref => this.ctx.api.agents.mcp.syncCatalog(ref, alias));
    }
    const catalog = this.catalog.byId(linked.catalogServerId);
    if (!catalog) return false;
    const synced = catalogDefinition(catalog, alias, linked.enabled, linked.id);
    this.agents.update(agentId, item => ({
      ...item, mcp: item.mcp.map(server => server.name === alias ? synced : server),
    }));
    return true;
  }

  /** Detaches the alias from the catalog so it can be edited directly. */
  async unlinkCatalog(agentId: string, alias: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent?.mcp.some(server => server.name === alias)) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'MCP customize',
        ref => this.ctx.api.agents.mcp.unlinkCatalog(ref, alias));
    }
    this.agents.update(agentId, item => ({
      ...item,
      mcp: item.mcp.map(server => server.name !== alias ? server : ({
        ...server, origin: 'custom' as const, catalogServerId: null,
        syncedRevision: null, catalogRevision: null, updateAvailable: false,
      })),
    }));
    return true;
  }

  async remove(agentId: string, mcpId: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    const server = agent?.mcp.find(m => m.id === mcpId);
    if (!agent || !server) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'mcp remove',
        ref => this.ctx.api.agents.mcp.remove(ref, server.name));
    }
    this.agents.update(agentId, x => ({ ...x, mcp: x.mcp.filter(m => m.id !== mcpId) }));
    return true;
  }

  /** Retest a single MCP server's reachability. */
  async test(agentId: string, serverName: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent) return false;
    this.patchServer(agentId, serverName, server => server.status === 'disabled'
      ? server
      : { ...server, status: 'checking' as const, error: null });

    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return false;
      try {
        const r = await this.ctx.api.agents.mcp.test(resolved.ref, serverName);
        this.patchServer(agentId, serverName, server => ({
          ...server, status: r.status as McpServer['status'], tools: r.tools,
          latencyMs: r.latencyMs, error: r.error, checkedAt: r.checkedAt,
        }));
        if (r.error) this.ctx.toast(`mcp ${serverName}: ${r.error}`);
        return r.status === 'connected';
      } catch (e) {
        const message = (e as { message?: string } | null)?.message ?? String(e);
        this.ctx.toast(`mcp test failed: ${message}`);
        this.patchServer(agentId, serverName, server => ({
          ...server, status: 'error' as const, latencyMs: null,
          error: message, checkedAt: Date.now(),
        }));
        return false;
      }
    }
    await new Promise(res => setTimeout(res, 700));
    const ok = Math.random() < 0.85;
    this.patchServer(agentId, serverName, server => ({
      ...server, status: ok ? 'connected' : 'error',
      latencyMs: ok ? 30 + Math.floor(Math.random() * 200) : null,
      error: ok ? null : 'simulated endpoint unreachable', checkedAt: Date.now(),
    }));
    return ok;
  }

  private patchServer(
    agentId: string, serverName: string, change: (server: McpServer) => McpServer,
  ): void {
    this.agents.update(agentId, x => ({
      ...x, mcp: x.mcp.map(m => m.name === serverName ? change(m) : m),
    }));
  }
}

/** A server the operator configured on the profile itself. */
function directServer(
  name: string, transport: McpServer['transport'], opts?: McpEndpointOptions,
): McpServer {
  return {
    id: nid('m'), name, transport, enabled: true, origin: 'custom', catalogServerId: null,
    syncedRevision: null, catalogRevision: null, updateAvailable: false, status: 'unknown',
    tools: 0, latencyMs: null, error: null, checkedAt: null,
    url: opts?.url, command: opts?.command, args: opts?.args,
  };
}

/** The profile-side view of a catalog entry, linked at its current revision. */
function catalogDefinition(
  catalog: McpCatalogServer, alias: string, enabled: boolean, id = nid('m'),
): McpServer {
  return {
    id, name: alias, transport: catalog.transport, enabled, origin: 'catalog',
    catalogServerId: catalog.id, syncedRevision: catalog.revision,
    catalogRevision: catalog.revision, updateAvailable: false,
    status: enabled ? 'unknown' : 'disabled', tools: 0, latencyMs: null,
    error: null, checkedAt: null,
    url: catalog.kind === 'stdio' ? undefined : (catalog.connectionUrl ?? catalog.url ?? undefined),
    command: catalog.kind === 'stdio' ? (catalog.stdioCommand ?? undefined) : undefined,
    args: catalog.kind === 'stdio' && catalog.args.length ? catalog.args.join(' ') : undefined,
  };
}
