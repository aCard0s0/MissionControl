import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { clock } from '../core/format';
import { mcpOperationActive } from '../core/mcp-lifecycle';
import { McpCatalogKind, McpCatalogServer, McpRetainedResource } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { McpEditorDraft, mcpDraftFromServer, newMcpDraft } from './mcp-editor';
import { mcpDisplayEndpoint, mcpServerGroups } from './mcp-server-groups';
import { McpServerEditor } from './mcp-server-editor';
import { McpServerLogs } from './mcp-server-logs';

/**
 * The global MCP catalog: what is registered, what is running, and what data a
 * delete left behind. The page itself is the roster and the two destructive
 * confirmations — the entry form, the log tail and the grouping rules each live
 * in their own file beside it.
 */
@Component({
  selector: 'mc-mcp-servers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal, McpServerEditor, McpServerLogs],
  templateUrl: './mcp-servers.html',
  styleUrl: './mcp-servers.scss',
})
export class McpServersPage {
  protected readonly store = inject(HermesStore);
  protected readonly operationActive = mcpOperationActive;
  protected readonly displayEndpoint = mcpDisplayEndpoint;
  protected readonly clock = clock;

  /** The entry the editor is open on; null keeps it closed. */
  protected draft: McpEditorDraft | null = null;
  /** The server the log viewer is open on. */
  protected readonly logServer = signal<McpCatalogServer | null>(null);

  /** Ids with a lifecycle call in flight, on top of what the backend reports. */
  private readonly actionBusy = signal<Set<string>>(new Set());

  protected readonly removing = signal<McpCatalogServer | null>(null);
  protected removeConfirm = '';
  protected readonly removeBusy = signal(false);

  protected readonly purging = signal<McpRetainedResource | null>(null);
  protected purgeConfirm = '';
  protected readonly purgeBusy = signal(false);

  protected readonly serverGroups = computed(() =>
    mcpServerGroups(this.store.mcpServers(), this.store.dockerHosts()));

  constructor() {
    void this.store.refreshMcpServers();
    void this.store.refreshRetainedMcpResources();
  }

  // ── editor ──────────────────────────────────────────────────────────────
  protected openCreate(kind: McpCatalogKind = 'managed'): void {
    const preferredHost = this.store.dockerHosts().find(host => host.status === 'connected')
      ?? this.store.dockerHosts()[0];
    this.draft = newMcpDraft(kind, preferredHost?.id ?? '');
  }

  protected openEdit(server: McpCatalogServer, duplicate = false): void {
    this.draft = mcpDraftFromServer(server, duplicate);
  }

  // ── lifecycle ───────────────────────────────────────────────────────────
  protected isBusy(server: McpCatalogServer): boolean {
    return this.actionBusy().has(server.id) || mcpOperationActive(server.operationState);
  }

  protected async run(
    server: McpCatalogServer, operation: 'start' | 'stop' | 'apply' | 'check',
  ): Promise<void> {
    if (this.isBusy(server) || (operation === 'check' && server.checkStatus === 'checking')) return;
    this.actionBusy.update(ids => new Set(ids).add(server.id));
    try {
      if (operation === 'start') await this.store.startCatalogMcpServer(server.id);
      else if (operation === 'stop') await this.store.stopCatalogMcpServer(server.id);
      else if (operation === 'apply') await this.store.applyCatalogMcpServer(server.id);
      else await this.store.checkCatalogMcpServer(server.id);
    } finally {
      this.actionBusy.update(ids => {
        const next = new Set(ids);
        next.delete(server.id);
        return next;
      });
    }
  }

  // ── remove a server ─────────────────────────────────────────────────────
  protected beginRemove(server: McpCatalogServer): void {
    this.removing.set(server);
    this.removeConfirm = '';
  }

  protected async confirmRemove(): Promise<void> {
    const server = this.removing();
    if (!server || this.removeConfirm !== server.name || this.removeBusy()) return;
    this.removeBusy.set(true);
    const removed = await this.store.deleteCatalogMcpServer(server.id);
    this.removeBusy.set(false);
    if (removed) {
      this.removing.set(null);
      this.removeConfirm = '';
      // nothing left to tail once the entry is gone
      if (this.logServer()?.id === server.id) this.logServer.set(null);
    }
  }

  // ── purge what a delete left behind ─────────────────────────────────────
  protected beginPurge(resource: McpRetainedResource): void {
    this.purging.set(resource);
    this.purgeConfirm = '';
  }

  protected async confirmPurge(): Promise<void> {
    const resource = this.purging();
    if (!resource || this.purgeConfirm !== resource.name || this.purgeBusy()) return;
    this.purgeBusy.set(true);
    const purged = await this.store.purgeRetainedMcpResource(resource.id);
    this.purgeBusy.set(false);
    if (purged) {
      this.purging.set(null);
      this.purgeConfirm = '';
    }
  }
}
