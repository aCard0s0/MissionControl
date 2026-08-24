import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HostStore } from '../core/store/host-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { clock } from '../core/format';
import { mcpDisplayEndpoint, mcpEntryBusy, mcpOperationActive } from '../core/mcp/catalog-rules';
import { McpCatalogKind, McpCatalogServer, McpRetainedResource } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { McpEditorDraft, mcpDraftFromServer, newMcpDraft } from '../core/mcp/catalog-draft';
import { mcpServerGroups } from './mcp-server-groups';
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
  protected readonly catalog = inject(McpCatalogStore);
  protected readonly hosts = inject(HostStore);
  protected readonly operationActive = mcpOperationActive;
  protected readonly displayEndpoint = mcpDisplayEndpoint;
  protected readonly clock = clock;

  /** The entry the editor is open on; null keeps it closed. */
  protected draft: McpEditorDraft | null = null;
  /** The server the log viewer is open on. */
  protected readonly logServer = signal<McpCatalogServer | null>(null);

  protected readonly removing = signal<McpCatalogServer | null>(null);
  protected removeConfirm = '';
  protected readonly removeBusy = signal(false);

  protected readonly purging = signal<McpRetainedResource | null>(null);
  protected purgeConfirm = '';
  protected readonly purgeBusy = signal(false);

  protected readonly serverGroups = computed(() =>
    mcpServerGroups(this.catalog.servers(), this.hosts.hosts()));

  constructor() {
    void this.catalog.refresh();
    void this.catalog.refreshRetainedResources();
  }

  // ── editor ──────────────────────────────────────────────────────────────
  protected openCreate(kind: McpCatalogKind = 'managed'): void {
    const preferredHost = this.hosts.hosts().find(host => host.status === 'connected')
      ?? this.hosts.hosts()[0];
    this.draft = newMcpDraft(kind, preferredHost?.id ?? '');
  }

  protected openEdit(server: McpCatalogServer, duplicate = false): void {
    this.draft = mcpDraftFromServer(server, duplicate);
  }

  // ── lifecycle ───────────────────────────────────────────────────────────
  protected readonly isBusy = mcpEntryBusy;

  protected async run(
    server: McpCatalogServer, operation: 'start' | 'stop' | 'apply' | 'check',
  ): Promise<void> {
    // the store patches the entry before its first await, so the guard reads the same flag the
    // template disables on — there is no window between them to track separately
    if (mcpEntryBusy(server)) return;
    if (operation === 'start') await this.catalog.start(server.id);
    else if (operation === 'stop') await this.catalog.stop(server.id);
    else if (operation === 'apply') await this.catalog.apply(server.id);
    else await this.catalog.check(server.id);
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
    const removed = await this.catalog.remove(server.id);
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
    const purged = await this.catalog.purgeRetainedResource(resource.id);
    this.purgeBusy.set(false);
    if (purged) {
      this.purging.set(null);
      this.purgeConfirm = '';
    }
  }
}
