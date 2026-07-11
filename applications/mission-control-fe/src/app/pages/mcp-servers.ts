import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import {
  LogEntry, McpCatalogKind, McpCatalogServer, McpCatalogServerInput, McpConfigEntry,
  McpHealthcheck, McpNamedVolume, McpRetainedResource, McpSupportService, McpTransport,
} from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';

export interface McpEditorEntry extends McpConfigEntry {
  value: string;
}

export interface McpEditorDraft {
  id: string | null;
  hostLocked: boolean;
  name: string;
  description: string;
  kind: McpCatalogKind;
  hostId: string;
  transport: McpTransport;
  url: string;
  image: string;
  platform: string;
  entrypoint: string;
  command: string;
  stdioCommand: string;
  args: string;
  internalPort: number | null;
  publishedPort: number | null;
  path: string;
  crossHostUrl: string;
  headers: McpEditorEntry[];
  environment: McpEditorEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpSupportService[];
}

export function splitMcpLines(value: string): string[] {
  return value.split(/\r?\n/).map(item => item.trim()).filter(Boolean);
}

export function httpEndpointValid(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.hostname
      && !url.username && !url.password && !url.hash;
  } catch {
    return false;
  }
}

export function mcpOperationActive(state: string): boolean {
  return !['', 'idle', 'none', 'error', 'failed', 'complete', 'completed'].includes(state.toLowerCase());
}

export function mcpDraftToInput(draft: McpEditorDraft): McpCatalogServerInput {
  const entries = (items: McpEditorEntry[]): McpConfigEntry[] => items
    .filter(item => item.key.trim())
    .map(item => ({
      key: item.key.trim(), value: item.value, secret: item.secret,
      clear: item.clear,
    }));
  const volumes = draft.volumes
    .filter(volume => volume.name.trim() && volume.target.trim())
    .map(volume => ({ name: volume.name.trim(), target: volume.target.trim() }));
  const managed = draft.kind === 'managed';
  const external = draft.kind === 'external';
  const stdio = draft.kind === 'stdio';
  return {
    name: draft.name.trim(), description: draft.description.trim(), kind: draft.kind,
    hostId: managed ? draft.hostId : null,
    transport: stdio ? 'stdio' : draft.transport,
    url: external ? draft.url.trim() : null,
    image: managed ? draft.image.trim() : null,
    platform: managed ? (draft.platform.trim() || null) : null,
    entrypoint: managed ? splitMcpLines(draft.entrypoint) : [],
    command: managed ? splitMcpLines(draft.command) : [],
    stdioCommand: stdio ? draft.stdioCommand.trim() : null,
    args: stdio ? splitMcpLines(draft.args) : [],
    internalPort: managed ? draft.internalPort : null,
    publishedPort: managed ? draft.publishedPort : null,
    path: managed ? draft.path.trim() : null,
    crossHostUrl: managed ? (draft.crossHostUrl.trim() || null) : null,
    headers: stdio ? [] : entries(draft.headers),
    environment: external ? [] : entries(draft.environment),
    volumes: managed ? volumes : [],
    healthcheck: managed ? draft.healthcheck : null,
    supportServices: managed ? draft.supportServices.map(service => ({
      ...service,
      environment: (service.environment ?? []).map(entry => ({
        key: entry.key, value: entry.value ?? '', secret: entry.secret,
        clear: entry.clear,
      })),
      volumes: (service.volumes ?? []).map(volume => ({ ...volume })),
    })) : [],
  };
}

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
    this.draft = {
      id: null, hostLocked: false, name: '', description: '', kind,
      hostId: preferredHost?.id ?? '', transport: kind === 'stdio' ? 'stdio' : 'http',
      url: '', image: '', platform: '', entrypoint: '', command: '', stdioCommand: '', args: '',
      internalPort: kind === 'managed' ? 1100 : null, publishedPort: null,
      path: '/mcp', crossHostUrl: '', headers: [], environment: [], volumes: [],
      healthcheck: null, supportServices: [],
    };
  }

  protected openEdit(server: McpCatalogServer, duplicate = false): void {
    const copyEntries = (items: McpConfigEntry[]): McpEditorEntry[] => items.map(item => ({
      ...item,
      value: item.secret ? '' : (item.value ?? ''),
      // A duplicate cannot inherit encrypted values without a source link.
      set: duplicate && item.secret ? false : item.set,
      recoverable: duplicate && item.secret ? false : item.recoverable,
    }));
    this.draft = {
      id: duplicate ? null : server.id,
      hostLocked: !duplicate,
      name: duplicate ? `${server.name} copy` : server.name,
      description: server.description,
      kind: server.kind,
      hostId: server.hostId ?? '',
      transport: server.transport,
      url: server.url ?? '',
      image: server.image ?? '',
      platform: server.platform ?? '',
      entrypoint: server.entrypoint.join('\n'),
      command: server.command.join('\n'),
      stdioCommand: server.stdioCommand ?? '',
      args: server.args.join('\n'),
      internalPort: server.internalPort,
      publishedPort: server.publishedPort,
      path: server.path ?? '/mcp',
      crossHostUrl: server.crossHostUrl ?? '',
      headers: copyEntries(server.headers),
      environment: copyEntries(server.environment),
      volumes: server.volumes.map(volume => ({ ...volume })),
      healthcheck: server.healthcheck ? { ...server.healthcheck, test: [...server.healthcheck.test] } : null,
      supportServices: server.supportServices.map(service => ({
        ...service,
        environment: copyEntries(service.environment ?? []),
        volumes: service.volumes?.map(volume => ({ ...volume })),
        healthcheck: service.healthcheck
          ? { ...service.healthcheck, test: [...service.healthcheck.test] }
          : null,
      })),
    };
  }

  protected kindChanged(): void {
    if (!this.draft) return;
    this.draft.transport = this.draft.kind === 'stdio' ? 'stdio' : 'http';
    if (this.draft.kind === 'managed') {
      this.draft.internalPort ??= 1100;
      this.draft.path ||= '/mcp';
    }
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
    target.healthcheck = target.healthcheck
      ? null
      : { test: ['CMD'], interval: '30s', timeout: '5s', retries: 3, startPeriod: '5s' };
  }

  protected draftValid(): boolean {
    const draft = this.draft;
    if (!draft || !draft.name.trim()) return false;
    if (this.store.mcpServers().some(server =>
      server.id !== draft.id && server.name.toLowerCase() === draft.name.trim().toLowerCase())) return false;
    if (!this.entriesValid(draft.environment, false) || !this.entriesValid(draft.headers, true)) return false;
    if (draft.kind === 'managed') {
      if (!draft.hostId || !draft.image.trim() || !draft.internalPort
          || draft.internalPort < 1 || draft.internalPort > 65_535) return false;
      if (draft.publishedPort !== null
          && (draft.publishedPort < 1 || draft.publishedPort > 65_535)) return false;
      if (!draft.path.startsWith('/') || draft.path.startsWith('//') || draft.path.includes('#')) return false;
      if (draft.crossHostUrl.trim() && !httpEndpointValid(draft.crossHostUrl.trim())) return false;
      if (draft.volumes.some(volume =>
        !this.volumeValid(volume))) return false;
      if (!this.healthcheckValid(draft.healthcheck)) return false;
      const supportNames = new Set<string>();
      for (const service of draft.supportServices) {
        const supportName = service.name.trim();
        if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(supportName)
            || supportNames.has(supportName) || !service.image.trim()) return false;
        supportNames.add(supportName);
        if (!this.entriesValid((service.environment ?? []) as McpEditorEntry[], false)
            || (service.volumes ?? []).some(volume => !this.volumeValid(volume))
            || !this.healthcheckValid(service.healthcheck ?? null)) return false;
      }
    } else if (draft.kind === 'external') {
      if (!httpEndpointValid(draft.url.trim())) return false;
    } else if (!draft.stdioCommand.trim()) {
      return false;
    }
    return true;
  }

  private entriesValid(entries: McpEditorEntry[], headers: boolean): boolean {
    const pattern = headers
      ? /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/
      : /^[A-Za-z_][A-Za-z0-9_]*$/;
    const seen = new Set<string>();
    return entries.every(entry => {
      const key = entry.key.trim();
      const identity = headers ? key.toLowerCase() : key;
      if (!key || !pattern.test(key) || seen.has(identity)) return false;
      seen.add(identity);
      return !entry.secret || !!entry.value || !!entry.set;
    });
  }

  private volumeValid(volume: McpNamedVolume): boolean {
    return /^[a-z0-9][a-z0-9_.-]{0,62}$/.test(volume.name.trim())
      && volume.target.trim().startsWith('/')
      && !volume.target.includes('/../')
      && !volume.target.endsWith('/..')
      && volume.target.trim() !== '/var/run/docker.sock';
  }

  private healthcheckValid(value: McpHealthcheck | null | undefined): boolean {
    if (!value) return true;
    if (!value.test.length || !['CMD', 'CMD-SHELL', 'NONE'].includes(value.test[0])) return false;
    if (value.test[0] === 'NONE' && value.test.length !== 1) return false;
    const duration = /^[1-9][0-9]*(?:\.[0-9]+)?(?:ns|us|ms|s|m|h)$/;
    if ([value.interval, value.timeout, value.startPeriod]
        .some(item => !!item && !duration.test(item))) return false;
    return value.retries === null || value.retries === undefined
      || (value.retries >= 1 && value.retries <= 100);
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
