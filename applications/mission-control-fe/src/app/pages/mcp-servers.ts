import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { mcpOperationActive } from '../core/mcp-lifecycle';
import {
  LogEntry, McpCatalogKind, McpCatalogServer, McpHealthcheck, McpRetainedResource,
  McpSupportService,
} from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import {
  McpEditorDraft, applyMcpKindDefaults, defaultHealthcheck, mcpDraftFromServer, mcpDraftToInput,
  mcpDraftValid, newMcpDraft, splitMcpLines,
} from './mcp-editor';

@Component({
  selector: 'mc-mcp-servers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './mcp-servers.html',
  styleUrl: './mcp-servers.scss',
})
export class McpServersPage {
  protected readonly store = inject(HermesStore);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly operationActive = mcpOperationActive;
  protected readonly splitLines = splitMcpLines;

  protected draft: McpEditorDraft | null = null;
  protected readonly saveBusy = signal(false);
  protected readonly actionBusy = signal<Set<string>>(new Set());
  protected readonly removing = signal<McpCatalogServer | null>(null);
  protected removeConfirm = '';
  protected readonly removeBusy = signal(false);

  protected readonly purging = signal<McpRetainedResource | null>(null);
  protected purgeConfirm = '';
  protected readonly purgeBusy = signal(false);

  protected readonly logServer = signal<McpCatalogServer | null>(null);
  protected readonly logLines = signal<LogEntry[]>([]);
  protected readonly logsLoading = signal(false);
  protected readonly logsError = signal<string | null>(null);
  private logsTimer: ReturnType<typeof setInterval> | null = null;

  protected readonly managedGroups = computed(() => {
    const managed = this.store.mcpServers().filter(server => server.kind === 'managed');
    const hostIds = Array.from(new Set(managed.map(server => server.hostId ?? 'unassigned')));
    return hostIds.map(hostId => {
      const host = this.store.hostById(hostId);
      return {
        hostId,
        name: host?.name ?? (hostId === 'unassigned' ? 'Unassigned' : hostId),
        url: host?.url ?? '',
        servers: managed.filter(server => (server.hostId ?? 'unassigned') === hostId),
      };
    });
  });

  protected readonly externalServers = computed(() =>
    this.store.mcpServers().filter(server => server.kind === 'external'));
  protected readonly stdioServers = computed(() =>
    this.store.mcpServers().filter(server => server.kind === 'stdio'));
  protected readonly serverGroups = computed(() => [
    ...this.managedGroups().map(group => ({
      key: `managed-${group.hostId}`,
      label: `Managed stack — ${group.name}`,
      detail: group.url || 'Docker host unavailable',
      servers: group.servers,
    })),
    ...(this.externalServers().length ? [{
      key: 'external', label: 'External endpoints',
      detail: 'Local or remote HTTP/SSE MCP servers', servers: this.externalServers(),
    }] : []),
    ...(this.stdioServers().length ? [{
      key: 'stdio', label: 'Reusable stdio definitions',
      detail: 'Commands materialized inside an Agent container', servers: this.stdioServers(),
    }] : []),
  ]);

  constructor() {
    void this.store.refreshMcpServers();
    void this.store.refreshRetainedMcpResources();
    this.destroyRef.onDestroy(() => this.closeLogs());
  }

  protected openCreate(kind: McpCatalogKind = 'managed'): void {
    const preferredHost = this.store.dockerHosts().find(host => host.status === 'connected')
      ?? this.store.dockerHosts()[0];
    this.draft = newMcpDraft(kind, preferredHost?.id ?? '');
  }

  protected openEdit(server: McpCatalogServer, duplicate = false): void {
    this.draft = mcpDraftFromServer(server, duplicate);
  }

  protected kindChanged(): void {
    if (this.draft) applyMcpKindDefaults(this.draft);
  }

  protected addEntry(field: 'headers' | 'environment'): void {
    this.draft?.[field].push({ key: '', value: '', secret: false, set: false, recoverable: true });
  }

  protected removeEntry(field: 'headers' | 'environment', index: number): void {
    this.draft?.[field].splice(index, 1);
  }

  protected addVolume(): void {
    this.draft?.volumes.push({ name: '', target: '' });
  }

  protected removeVolume(index: number): void {
    this.draft?.volumes.splice(index, 1);
  }

  protected addSupportService(): void {
    this.draft?.supportServices.push({
      name: '', image: '', platform: null, entrypoint: [], command: [],
      environment: [], volumes: [], healthcheck: null,
    });
  }

  protected removeSupportService(index: number): void {
    this.draft?.supportServices.splice(index, 1);
  }

  protected addSupportEnvironment(service: McpSupportService): void {
    service.environment ??= [];
    service.environment.push({ key: '', value: '', secret: false, set: false, recoverable: true });
  }

  protected removeSupportEnvironment(service: McpSupportService, index: number): void {
    service.environment?.splice(index, 1);
  }

  protected addSupportVolume(service: McpSupportService): void {
    service.volumes ??= [];
    service.volumes.push({ name: '', target: '' });
  }

  protected removeSupportVolume(service: McpSupportService, index: number): void {
    service.volumes?.splice(index, 1);
  }

  protected toggleHealthcheck(target: { healthcheck?: McpHealthcheck | null }): void {
    target.healthcheck = target.healthcheck ? null : defaultHealthcheck();
  }

  protected draftValid(): boolean {
    return !!this.draft && mcpDraftValid(this.draft, this.store.mcpServers());
  }

  protected async save(): Promise<void> {
    const draft = this.draft;
    if (!draft || !this.draftValid() || this.saveBusy()) return;
    this.saveBusy.set(true);
    const id = await this.store.saveCatalogMcpServer(mcpDraftToInput(draft), draft.id ?? undefined);
    this.saveBusy.set(false);
    if (id) this.draft = null;
  }

  protected isBusy(server: McpCatalogServer): boolean {
    return this.actionBusy().has(server.id) || mcpOperationActive(server.operationState);
  }

  protected async run(server: McpCatalogServer, operation: 'start' | 'stop' | 'apply' | 'check'): Promise<void> {
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
      if (this.logServer()?.id === server.id) this.closeLogs();
    }
  }

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

  protected openLogs(server: McpCatalogServer): void {
    this.closeLogs();
    this.logServer.set(server);
    this.logLines.set([]);
    this.logsError.set(null);
    void this.loadLogs();
    this.logsTimer = setInterval(() => void this.loadLogs(), 3_000);
  }

  protected closeLogs(): void {
    if (this.logsTimer) {
      clearInterval(this.logsTimer);
      this.logsTimer = null;
    }
    this.logServer.set(null);
    this.logsLoading.set(false);
  }

  protected async loadLogs(): Promise<void> {
    const server = this.logServer();
    if (!server || this.logsLoading()) return;
    this.logsLoading.set(true);
    try {
      const lines = await this.store.mcpServerLogTail(server.id, 150);
      if (this.logServer()?.id === server.id) {
        this.logLines.set(lines);
        this.logsError.set(null);
      }
    } catch (error) {
      if (this.logServer()?.id === server.id) {
        this.logsError.set(error instanceof Error ? error.message : String(error));
      }
    } finally {
      if (this.logServer()?.id === server.id) this.logsLoading.set(false);
    }
  }

  protected displayEndpoint(server: McpCatalogServer): string {
    if (server.kind === 'stdio') return [server.stdioCommand, ...server.args].filter(Boolean).join(' ');
    return server.connectionUrl ?? server.url ?? server.crossHostUrl ?? 'endpoint pending';
  }

  protected logTime(ts: number): string {
    return new Date(ts).toLocaleTimeString('en-GB', { hour12: false });
  }
}
