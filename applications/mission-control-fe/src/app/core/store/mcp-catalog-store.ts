import { WritableSignal, signal } from '@angular/core';
import { McpServerOperation } from '../hermes-api';
import { mcpOperationActive } from '../mcp-lifecycle';
import { LogEntry, McpCatalogServer, McpCatalogServerInput, McpRetainedResource } from '../models';
import { seedMcpCatalogServers } from '../mock-data';
import { StoreContext, nid } from './store-context';
import { toMcpCatalogServer } from './wire-mappers';

/** Image pulls (notably Playwright) can take minutes on a cold host, so the
 *  operation poll has to outlast the backend's ten-minute Compose timeout. */
const OPERATION_POLL_INTERVAL = 1_500;
const OPERATION_POLL_ATTEMPTS = 420;

/** How long {@link McpCatalogStore.waitUntilRunning} gives a start to land. */
const START_TIMEOUT = 10 * 60_000;

/** The state a lifecycle verb moves an entry into while it is in flight. */
const IN_FLIGHT_STATE = { start: 'starting', stop: 'stopping', apply: 'applying' } as const;

/**
 * The global MCP definitions — external endpoints, reusable stdio commands, and
 * the managed Compose services Mission Control runs itself. Also owns the
 * volumes a delete deliberately leaves behind.
 */
export class McpCatalogStore {
  readonly servers: WritableSignal<McpCatalogServer[]>;
  readonly loading = signal(false);
  readonly retainedResources = signal<McpRetainedResource[]>([]);

  private readonly operationPolls = new Set<string>();

  constructor(private readonly ctx: StoreContext) {
    this.servers = signal(ctx.mock ? seedMcpCatalogServers() : []);
  }

  byId = (id: string | null): McpCatalogServer | null =>
    this.servers().find(s => s.id === id) ?? null;

  async refresh(silent = false): Promise<void> {
    if (this.ctx.mock) return;
    if (!silent) this.loading.set(true);
    try {
      this.servers.set((await this.ctx.api.mcp.list()).map(toMcpCatalogServer));
    } catch (e) {
      if (!silent) this.ctx.toastFailure('MCP server refresh', e);
    } finally {
      if (!silent) this.loading.set(false);
    }
  }

  async refreshRetainedResources(): Promise<void> {
    if (this.ctx.mock) return;
    try {
      this.retainedResources.set(await this.ctx.api.mcp.retainedResources());
    } catch { /* retained data inventory is non-critical */ }
  }

  /** Create or update a catalog entry. Returns its id, or an empty string. */
  async save(input: McpCatalogServerInput, id?: string): Promise<string> {
    const duplicate = this.servers().find(server =>
      server.id !== id && server.name.toLowerCase() === input.name.toLowerCase());
    if (duplicate) {
      this.ctx.toast(`MCP server name already exists: ${duplicate.name}`);
      return '';
    }
    if (!this.ctx.mock) {
      try {
        const saved = id
          ? await this.ctx.api.mcp.update(id, input)
          : await this.ctx.api.mcp.create(input);
        const server = toMcpCatalogServer(saved);
        this.upsert(server);
        if (mcpOperationActive(server.operationState)) void this.pollOperation(server.id);
        return server.id;
      } catch (e) {
        this.ctx.toastFailure('MCP server save', e);
        return '';
      }
    }
    return this.saveMock(input, id);
  }

  async remove(id: string): Promise<boolean> {
    const server = this.byId(id);
    if (!server) return false;
    if (!this.ctx.mock) {
      try {
        const response = await this.ctx.api.mcp.remove(id);
        if (response) this.upsert(toMcpCatalogServer(response));
        await this.refresh(true);
        void this.pollOperation(id, true);
        return true;
      } catch (e) {
        this.ctx.toastFailure('MCP server delete', e);
        return false;
      }
    }
    this.retainMockVolumes(server);
    this.servers.update(servers => servers.filter(item => item.id !== id));
    return true;
  }

  start(id: string): Promise<boolean> {
    return this.run(id, 'start');
  }

  stop(id: string): Promise<boolean> {
    return this.run(id, 'stop');
  }

  apply(id: string): Promise<boolean> {
    return this.run(id, 'apply');
  }

  check(id: string): Promise<boolean> {
    return this.run(id, 'check');
  }

  /**
   * Waits for a managed entry to actually reach `running` after a start, so no
   * Agent configuration is written against a server that never came up. Mock
   * mode starts synchronously and skips the wait.
   */
  async waitUntilRunning(id: string): Promise<boolean> {
    if (this.ctx.mock) return this.byId(id)?.runtimeState === 'running';
    const deadline = Date.now() + START_TIMEOUT;
    while (Date.now() < deadline) {
      await this.refresh(true);
      const server = this.byId(id);
      if (!server) return false;
      if (server.runtimeState === 'running') return true;
      if (server.runtimeState === 'error' || server.operationState === 'error' || server.operationError) {
        this.ctx.toast(`MCP server start failed: ${server.operationError ?? server.runtimeState}`);
        return false;
      }
      await new Promise(resolve => setTimeout(resolve, OPERATION_POLL_INTERVAL));
    }
    this.ctx.toast(`MCP server start timed out: ${this.byId(id)?.name}`);
    return false;
  }

  async logTail(id: string, tail = 100): Promise<LogEntry[]> {
    const server = this.byId(id);
    if (!server || server.kind !== 'managed') return [];
    if (this.ctx.mock) return mockLogTail(server, tail);
    const lines = await this.ctx.api.mcp.logs(id, tail);
    return lines.map(line => ({ ...line, agentId: null })).sort((a, b) => b.ts - a.ts);
  }

  async purgeRetainedResource(id: string): Promise<boolean> {
    if (!this.ctx.mock) {
      try {
        await this.ctx.api.mcp.purgeRetainedResource(id);
      } catch (e) {
        this.ctx.toastFailure('retained resource purge', e);
        return false;
      }
    }
    this.retainedResources.update(resources => resources.filter(resource => resource.id !== id));
    return true;
  }

  private upsert(server: McpCatalogServer): void {
    this.servers.update(servers => [server, ...servers.filter(item => item.id !== server.id)]);
  }

  private patch(id: string, change: (server: McpCatalogServer) => McpCatalogServer): void {
    this.servers.update(servers => servers.map(item => item.id === id ? change(item) : item));
  }

  private async run(id: string, operation: McpServerOperation): Promise<boolean> {
    const server = this.byId(id);
    if (!server) return false;
    this.patch(id, item => ({
      ...item,
      operationState: operation === 'check' ? item.operationState : IN_FLIGHT_STATE[operation],
      operationError: null,
      ...(operation === 'check' ? { checkStatus: 'checking' as const, checkError: null } : {}),
    }));

    if (!this.ctx.mock) return this.runLive(id, operation);
    await Promise.resolve();
    this.patch(id, item => mockOperationResult(item, operation));
    return true;
  }

  private async runLive(id: string, operation: McpServerOperation): Promise<boolean> {
    try {
      const response = await this.ctx.api.mcp.run(id, operation);
      if (response) this.upsert(toMcpCatalogServer(response));
      else await this.refresh(true);
      if (operation !== 'check') void this.pollOperation(id);
      return operation !== 'check' || this.byId(id)?.checkStatus === 'connected';
    } catch (e) {
      const message = (e as { message?: string } | null)?.message ?? String(e);
      this.patch(id, item => ({
        ...item,
        operationState: operation === 'check' ? item.operationState : 'error',
        operationError: operation === 'check' ? item.operationError : message,
        ...(operation === 'check'
          ? { checkStatus: 'error' as const, checkError: message, checkedAt: Date.now(), latencyMs: null }
          : {}),
      }));
      this.ctx.toast(`MCP server ${operation} failed: ${message}`);
      return false;
    }
  }

  /** Polls only while a lifecycle operation is active. Delete polling stops
   *  once the entry disappears from the catalog. */
  private async pollOperation(id: string, deleting = false): Promise<void> {
    if (this.ctx.mock || this.operationPolls.has(id)) return;
    this.operationPolls.add(id);
    try {
      for (let attempt = 0; attempt < OPERATION_POLL_ATTEMPTS; attempt++) {
        await new Promise(resolve => setTimeout(resolve, OPERATION_POLL_INTERVAL));
        await this.refresh(true);
        const server = this.byId(id);
        if (!server || !mcpOperationActive(server.operationState)) break;
      }
      if (deleting) await this.refreshRetainedResources();
    } finally {
      this.operationPolls.delete(id);
    }
  }

  private saveMock(input: McpCatalogServerInput, id?: string): string {
    const existing = id ? this.byId(id) : null;
    const now = Date.now();
    const revision = (existing?.revision ?? 0) + 1;
    const serviceKey = existing?.serviceKey ?? (
      input.name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || nid('mcp-service')
    );
    const running = existing?.runtimeState === 'running';
    const connectionUrl = input.kind === 'managed'
      ? (input.crossHostUrl || `http://${serviceKey}:${input.internalPort}${input.path ?? ''}`)
      : input.kind === 'external' ? input.url : null;
    const server: McpCatalogServer = {
      ...input,
      id: existing?.id ?? nid('mcp'),
      connectionUrl,
      desiredState: existing?.desiredState ?? 'stopped',
      runtimeState: existing?.runtimeState ?? (input.kind === 'managed' ? 'stopped' : 'unknown'),
      operationState: 'idle', operationError: null,
      checkStatus: existing?.checkStatus ?? 'unknown',
      checkError: existing?.checkError ?? null,
      checkedAt: existing?.checkedAt ?? null,
      latencyMs: existing?.latencyMs ?? null,
      revision,
      appliedRevision: running ? (existing?.appliedRevision ?? 0) : revision,
      pendingChanges: running,
      serviceKey: input.kind === 'managed' ? serviceKey : null,
      createdAt: existing?.createdAt ?? now,
      updatedAt: now,
    };
    this.upsert(server);
    return server.id;
  }

  /** Named volumes outlive their server on a real host, so mock mode records
   *  them as retained data the operator still has to purge. */
  private retainMockVolumes(server: McpCatalogServer): void {
    const seen = new Set<string>();
    const volumes = [...server.volumes, ...server.supportServices.flatMap(service => service.volumes ?? [])]
      .filter(volume => !seen.has(volume.name) && seen.add(volume.name));
    this.retainedResources.update(resources => [
      ...volumes.map((volume): McpRetainedResource => ({
        id: nid('mcp-volume'), serverId: server.id, serverName: server.name,
        hostId: server.hostId ?? 'dh-local', type: 'volume', name: volume.name,
        createdAt: Date.now(),
      })),
      ...resources,
    ]);
  }
}

function mockOperationResult(item: McpCatalogServer, operation: McpServerOperation): McpCatalogServer {
  if (operation === 'check') return {
    ...item, checkStatus: 'connected' as const, checkError: null,
    checkedAt: Date.now(), latencyMs: 24,
  };
  if (operation === 'stop') return {
    ...item, desiredState: 'stopped' as const, runtimeState: 'stopped' as const,
    operationState: 'idle', operationError: null,
  };
  return {
    ...item, desiredState: 'running' as const, runtimeState: 'running' as const,
    operationState: 'idle', operationError: null,
    appliedRevision: item.revision, pendingChanges: false,
  };
}

function mockLogTail(server: McpCatalogServer, tail: number): LogEntry[] {
  const now = Date.now();
  return [
    { ts: now - 3_000, level: 'info' as const, source: server.serviceKey ?? server.name, agentId: null,
      msg: server.runtimeState === 'running' ? `MCP endpoint ready at ${server.connectionUrl}` : 'container is stopped' },
    { ts: now - 8_000, level: 'debug' as const, source: 'compose', agentId: null,
      msg: `project mission-control-mcp · revision ${server.appliedRevision}` },
  ].slice(0, tail);
}
