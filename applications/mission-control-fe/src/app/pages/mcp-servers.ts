import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HostStore } from '../core/store/host-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { McpGroupStore } from '../core/store/mcp-group-store';
import { GroupDraft, groupHolding } from '../core/filing';
import { clock } from '../core/format';
import { mcpDisplayEndpoint, mcpEntryBusy, mcpOperationActive } from '../core/mcp/catalog-rules';
import {
  DeployedPart, McpCatalogKind, McpCatalogServer, McpGroup, McpRetainedResource,
} from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { McpEditorDraft, mcpDraftFromServer, newMcpDraft } from '../core/mcp/catalog-draft';
import { mcpServerSections } from './mcp-server-sections';
import { McpServerEditor } from './mcp-server-editor';
import { McpServerLogs } from './mcp-server-logs';
import { AgentRef } from '../core/api/agent-ref';
import { DeployDialog } from './deploy-dialog';
import { Scrim } from '../shared/scrim';

export type McpTab = 'servers' | 'groups';

/**
 * The global MCP catalog: what is registered, what is running, and what data a
 * delete left behind. The page itself is the roster and the two destructive
 * confirmations — the entry form, the log tail and the section rules each live
 * in their own file beside it.
 *
 * Two different things called a section and a group live here, so they are named apart.
 * {@link serverSections} is the roster's own arrangement by kind and host — Managed stack,
 * External endpoints, Reusable stdio definitions — and nothing stores it. {@link groups} are
 * operator-made sets of entries with a deploy, the noun `mcp_groups` holds. The word "group"
 * used to mean the first one in this file; it now means only the second.
 *
 * They are two tabs rather than two sections of one page, because the roster answers "what is
 * registered and is it up" and the groups answer "what does an agent get" — different
 * questions, and stacking a user grouping under a kind grouping made neither readable. The tab
 * is local state and not a `?tab=` link, unlike the Skills page: nothing links here yet.
 */
@Component({
  selector: 'mc-mcp-servers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule, StatusDot, Reveal, McpServerEditor, McpServerLogs, Scrim, DeployDialog,
  ],
  templateUrl: './mcp-servers.html',
  styleUrl: './mcp-servers.scss',
})
export class McpServersPage {
  protected readonly tabs: McpTab[] = ['servers', 'groups'];
  protected readonly activeTab = signal<McpTab>('servers');

  protected readonly catalog = inject(McpCatalogStore);
  protected readonly groups = inject(McpGroupStore);
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

  protected readonly serverSections = computed(() =>
    mcpServerSections(this.catalog.servers(), this.hosts.hosts()));

  // ── groups ──────────────────────────────────────────────────────────────
  /** The group editor's state — open, editing which, and what is picked. */
  protected readonly groupDraft = new GroupDraft();

  /** The group being deployed, or null while no dialog is open. */
  protected readonly deploying = signal<McpGroup | null>(null);

  /** A field, not an inline arrow in the template: the dialog's `run` input would otherwise
   *  take a new function identity on every change detection pass. */
  protected readonly deployToAgent = async (agent: AgentRef): Promise<DeployedPart[] | null> => {
    const group = this.deploying();
    return group ? await this.groups.deploy(group.id, agent) : null;
  };

  constructor() {
    void this.catalog.refresh();
    void this.catalog.refreshRetainedResources();
    void this.groups.refresh();
  }

  /** The catalog entries a group names, and a placeholder for any the catalog has lost — a
   *  deploy reports those as skipped, so the page says so before the operator clicks. */
  protected groupServers(group: McpGroup): { id: string; name: string; missing: boolean }[] {
    const servers = this.catalog.servers();
    return group.serverIds.map(id => {
      const found = servers.find(s => s.id === id);
      return { id, name: found?.name ?? id, missing: !found };
    });
  }

  protected filedElsewhere(serverId: string): string {
    return groupHolding(this.groups.groups(), g => g.serverIds, serverId, this.groupDraft.editId());
  }

  protected newGroup(): void {
    this.groupDraft.begin();
  }

  protected editGroup(group: McpGroup): void {
    this.groupDraft.begin(group, group.serverIds);
  }

  protected saveGroup(): Promise<void> {
    return this.groupDraft.save(this.groups, f => ({
      name: f.name, description: f.description, serverIds: f.ids,
    }));
  }

  protected async removeGroup(group: McpGroup): Promise<void> {
    if (!confirm(
      `Delete the group "${group.name}"? Every agent it connected stays connected — only the `
      + `set goes. Disconnecting is each agent's own MCP tab.`
    )) return;
    if (await this.groups.remove(group.id)) this.groupDraft.closeIf(group.id);
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
