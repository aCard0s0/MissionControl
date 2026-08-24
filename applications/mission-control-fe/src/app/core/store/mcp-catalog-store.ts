import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { errorMessage } from '../errors';
import { McpServerOperation } from '../hermes-api';
import { IN_FLIGHT_STATE, duplicateCatalogName, mcpOperationActive } from '../mcp/catalog-rules';
import { LogEntry, McpCatalogServer, McpCatalogServerInput, McpRetainedResource } from '../models';
import { StoreContext } from './store-context';
import { toLogEntry, toMcpCatalogServer, toMcpRetainedResource } from './wire-mappers';

/**
 * Image pulls (notably Playwright) can take minutes on a cold host, so the poll has to outlast
 * the backend's own ten-minute Compose timeout — by one interval, so a run that fails on that
 * timeout is reported with the reason the backend recorded rather than as one that never
 * answered.
 *
 * There were two of these: 420 attempts at 1.5s here, and a separate ten-minute deadline in
 * waitUntilRunning that did not outlast the backend at all.
 */
const OPERATION_POLL_INTERVAL = 1_500;
const COMPOSE_TIMEOUT = 10 * 60_000;
const OPERATION_TIMEOUT = COMPOSE_TIMEOUT + OPERATION_POLL_INTERVAL;

/**
 * The global MCP definitions — external endpoints, reusable stdio commands, and
 * the managed Compose services Mission Control runs itself. Also owns the
 * volumes a delete deliberately leaves behind.
 */
@Injectable({ providedIn: 'root' })
export class McpCatalogStore {
  readonly servers: WritableSignal<McpCatalogServer[]>;
  readonly loading = signal(false);
  readonly retainedResources = signal<McpRetainedResource[]>([]);

  /** One poll per entry, shared by everyone waiting on it. */
  private readonly operationPolls = new Map<string, Promise<McpCatalogServer | null>>();

  private readonly ctx = inject(StoreContext);

  constructor() {
    this.servers = signal([]);
  }

  byId = (id: string | null): McpCatalogServer | null =>
    this.servers().find(s => s.id === id) ?? null;

  async refresh(silent = false): Promise<void> {
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
    try {
      this.retainedResources.set(
        (await this.ctx.api.mcp.retainedResources()).map(toMcpRetainedResource));
    } catch { /* retained data inventory is non-critical */ }
  }

  /** Create or update a catalog entry. Returns its id, or an empty string. */
  async save(input: McpCatalogServerInput, id?: string): Promise<string> {
    const duplicate = duplicateCatalogName(input.name, this.servers(), id);
    if (duplicate) {
      this.ctx.toast(`MCP server name already exists: ${duplicate.name}`);
      return '';
    }
    try {
      const saved = id
        ? await this.ctx.api.mcp.update(id, input)
        : await this.ctx.api.mcp.create(input);
      const server = toMcpCatalogServer(saved);
      this.upsert(server);
      if (mcpOperationActive(server.operationState)) void this.awaitSettled(server.id);
      return server.id;
    } catch (e) {
      this.ctx.toastFailure('MCP server save', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    if (!this.byId(id)) return this.ctx.gone('MCP server');
    try {
      const response = await this.ctx.api.mcp.remove(id);
      if (response) this.upsert(toMcpCatalogServer(response));
      await this.refresh(true);
      // the volumes a delete leaves behind only exist once the teardown has finished
      void this.awaitSettled(id).then(() => this.refreshRetainedResources());
      return true;
    } catch (e) {
      this.ctx.toastFailure('MCP server delete', e);
      return false;
    }
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
   * Agent configuration is written against a server that never came up.
   *
   * Joins the entry's existing poll rather than opening a second one: `start` already began
   * one, and connecting a catalog entry used to run both at once — two full catalog reads
   * every 1.5 seconds, for up to ten minutes.
   */
  async waitUntilRunning(id: string): Promise<boolean> {
    const server = await this.awaitSettled(id);
    if (!server) return this.ctx.gone('MCP server');
    if (server.runtimeState === 'running') return true;
    if (mcpOperationActive(server.operationState)) {
      this.ctx.toast(`MCP server start timed out: ${server.name}`);
      return false;
    }
    this.ctx.toast(
      `MCP server start failed: ${server.operationError ?? server.runtimeState}`);
    return false;
  }

  async logTail(id: string, tail = 100): Promise<LogEntry[]> {
    const server = this.byId(id);
    if (!server || server.kind !== 'managed') return [];
    const lines = await this.ctx.api.mcp.logs(id, tail);
    return lines.map(line => toLogEntry(line, null)).sort((a, b) => b.ts - a.ts);
  }

  async purgeRetainedResource(id: string): Promise<boolean> {
    try {
      await this.ctx.api.mcp.purgeRetainedResource(id);
    } catch (e) {
      this.ctx.toastFailure('retained resource purge', e);
      return false;
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
    if (!server) return this.ctx.gone('MCP server');
    this.patch(id, item => ({
      ...item,
      operationState: operation === 'check' ? item.operationState : IN_FLIGHT_STATE[operation],
      operationError: null,
      ...(operation === 'check' ? { checkStatus: 'checking' as const, checkError: null } : {}),
    }));
    return this.runLive(id, operation);
  }

  private async runLive(id: string, operation: McpServerOperation): Promise<boolean> {
    try {
      const response = await this.ctx.api.mcp.run(id, operation);
      if (response) this.upsert(toMcpCatalogServer(response));
      else await this.refresh(true);
      if (operation !== 'check') void this.awaitSettled(id);
      return operation !== 'check' || this.byId(id)?.checkStatus === 'connected';
    } catch (e) {
      const message = errorMessage(e);
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

  /**
   * Refreshes until nothing is in flight for `id`, and answers with the entry as it settled:
   * null once it is gone, and still-active only when the wait ran out.
   *
   * Single-flighted per entry, and every caller that cares joins the same poll.
   */
  private async awaitSettled(id: string): Promise<McpCatalogServer | null> {
    const running = this.operationPolls.get(id);
    if (running) return running;
    const poll = this.pollUntilSettled(id);
    this.operationPolls.set(id, poll);
    try {
      return await poll;
    } finally {
      this.operationPolls.delete(id);
    }
  }

  private async pollUntilSettled(id: string): Promise<McpCatalogServer | null> {
    const deadline = Date.now() + OPERATION_TIMEOUT;
    // what the entry already says first: a lifecycle call answers with the state the backend
    // recorded, so an operation that is already over costs no interval and no extra read
    let server = this.byId(id);
    while (server && mcpOperationActive(server.operationState) && Date.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, OPERATION_POLL_INTERVAL));
      await this.refresh(true);
      server = this.byId(id);
    }
    return server ?? null;
  }
}
