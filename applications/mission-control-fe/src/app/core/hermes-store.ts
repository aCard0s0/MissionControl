import { Injectable } from '@angular/core';
import { runtimeConfig } from './app-config';
import {
  ApiAgentSetup, ApiAuxiliaryModel, ApiImageTags, ApiPullState, ApiSetupAuthProvider,
  ApiSubscribeWebhookRequest,
} from './hermes-api';
import {
  BoardColumn, ContainerStatus, CronJob, McpCatalogServerInput, McpServer, ProfileTemplateInput,
  SkillRef,
} from './models';
import { AgentMcpStore } from './store/agent-mcp-store';
import { AgentSetupStore } from './store/agent-setup-store';
import { AgentSkillStore } from './store/agent-skill-store';
import { AgentStore } from './store/agent-store';
import { BoardStore } from './store/board-store';
import { ContainerLifecycle } from './store/container-lifecycle';
import { ContainerStore } from './store/container-store';
import { HostStore } from './store/host-store';
import { ImageCatalogStore } from './store/image-catalog-store';
import { JobStore } from './store/job-store';
import { LiveSync } from './store/live-sync';
import { LogStore } from './store/log-store';
import { McpCatalogStore } from './store/mcp-catalog-store';
import { ProviderStore } from './store/provider-store';
import { StoreContext } from './store/store-context';
import { TemplateStore } from './store/template-store';
import { TerminalRequestStore } from './store/terminal-request-store';
import { WebhookStore } from './store/webhook-store';
import { McpEndpointOptions } from '../shared/mcp-endpoint-form';

export type { TerminalRequest } from './store/terminal-request-store';

/**
 * Hermes data store — the single surface every page reads and writes through.
 *
 * It starts empty and is filled by {@link LiveSync} hitting `apiBaseUrl`.
 * Components only ever see the signals and actions below, so swapping the
 * backend client never touches a page.
 *
 * The state itself lives in the domain slices under ./store — one per subject,
 * all sharing a {@link StoreContext} that holds the data mode, the API client and
 * the toast channel. This class is the facade over them: it wires the slices
 * together, owns the poll clock, and keeps the flat surface the pages use.
 * All container-scoped views read through the selected container, which enforces
 * the "never mix containers" rule at the store level.
 */
@Injectable({ providedIn: 'root' })
export class HermesStore {
  private readonly ctx = new StoreContext(runtimeConfig());

  private readonly hostStore = new HostStore(this.ctx);
  private readonly containerStore = new ContainerStore(this.ctx);
  private readonly logStore = new LogStore(this.ctx, this.containerStore);
  private readonly agentStore = new AgentStore(this.ctx, this.containerStore);
  private readonly skillStore = new AgentSkillStore(this.ctx, this.agentStore);
  private readonly catalogStore = new McpCatalogStore(this.ctx);
  private readonly agentMcpStore = new AgentMcpStore(this.ctx, this.agentStore, this.catalogStore);
  private readonly setupStore = new AgentSetupStore(this.ctx, this.containerStore, this.agentStore);
  private readonly providerStore = new ProviderStore(this.ctx);
  private readonly imageStore = new ImageCatalogStore(this.ctx, this.containerStore, this.hostStore);
  private readonly templateStore =
    new TemplateStore(this.ctx, this.containerStore, this.agentStore);
  private readonly jobStore = new JobStore(this.ctx, this.containerStore, this.agentStore);
  private readonly boardStore = new BoardStore(this.ctx, this.containerStore);
  private readonly webhookStore =
    new WebhookStore(this.ctx, this.agentStore, listener => this.containerStore.onSelect(listener));
  private readonly lifecycle = new ContainerLifecycle(this.ctx, this.containerStore, this.imageStore);
  private readonly terminal = new TerminalRequestStore();
  private readonly liveSync = new LiveSync(
    this.ctx, this.hostStore, this.containerStore, this.agentStore, this.logStore, this.boardStore,
    this.templateStore, this.catalogStore, this.providerStore, this.imageStore, this.jobStore, this.webhookStore);

  constructor() {
    void this.liveSync.probeBackend();
  }

  // ── app-wide state ─────────────────────────────────────────────────────
  readonly config = this.ctx.config;
  readonly backendStatus = this.ctx.backendStatus;
  readonly liveError = this.ctx.liveError;
  readonly liveNotice = this.liveSync.notice;

  toast = (message: string): void => this.ctx.toast(message);

  // ── terminal panel ─────────────────────────────────────────────────────
  readonly terminalRequest = this.terminal.request;
  openTerminal = this.terminal.open.bind(this.terminal);

  // ── docker hosts ───────────────────────────────────────────────────────
  readonly dockerHosts = this.hostStore.hosts;
  readonly dockerOverall = this.hostStore.overall;
  hostById = this.hostStore.byId;
  addDockerHost = (name: string, url: string): void => this.hostStore.add(name, url);
  removeDockerHost = (id: string): void => this.hostStore.remove(id);
  checkDockerHost = (id: string): void => this.hostStore.check(id);

  // ── containers ─────────────────────────────────────────────────────────
  readonly containers = this.containerStore.containers;
  readonly selectedContainerId = this.containerStore.selectedContainerId;
  readonly selectedContainer = this.containerStore.selected;
  readonly fleetHealth = this.containerStore.fleetHealth;
  selectContainer = (id: string): void => this.containerStore.select(id);
  deployContainer = (name: string, version: string, profiles: string[], hostId?: string): Promise<string> =>
    this.lifecycle.deploy(name, version, profiles, hostId);
  setContainerStatus = (id: string, status: ContainerStatus): void => this.lifecycle.setStatus(id, status);
  updateContainer = (id: string, version: string): Promise<string> => this.lifecycle.update(id, version);
  removeContainer = (id: string): Promise<boolean> => this.lifecycle.remove(id);

  // ── docker logs ────────────────────────────────────────────────────────
  readonly containerLogs = this.logStore.selectedLogs;
  readonly logsLoading = this.logStore.loading;
  readonly logsUpdatedAt = this.logStore.updatedAt;
  readonly logsError = this.logStore.error;
  refreshLogs = (): void => this.logStore.refresh();

  // ── image catalog ──────────────────────────────────────────────────────
  readonly imageCatalog = this.imageStore.catalog;
  imageTags = (hostId: string): Promise<ApiImageTags> => this.imageStore.tags(hostId);
  refreshImageCatalog = (hostId: string, force?: boolean): Promise<void> =>
    this.imageStore.refresh(hostId, force);
  refreshImageCatalogs = (force?: boolean): Promise<void> => this.imageStore.refreshAll(force);

  // ── agent profiles ─────────────────────────────────────────────────────
  readonly agents = this.agentStore.agents;
  readonly containerAgents = this.agentStore.forSelectedContainer;
  agentById = this.agentStore.byId;
  createAgent = (
    containerId: string, name: string, provider: string, model: string, apiKey: string,
    cloneFromId?: string, baseUrl?: string, templateId?: string, auxiliary?: ApiAuxiliaryModel,
  ): Promise<string> => this.agentStore.create(
    containerId, name, provider, model, apiKey, cloneFromId, baseUrl, templateId, auxiliary);
  /** Removes the profile and everything keyed to it (jobs, tasks, webhooks). */
  removeAgent = (id: string): void => this.agentStore.remove(id, agentId => {
    this.jobStore.dropByAgent(agentId);
    this.boardStore.dropByAgent(agentId);
    this.webhookStore.dropByAgent(agentId);
    this.setupStore.forget(agentId);
  });
  updateSoul = (id: string, soul: string): Promise<boolean> => this.agentStore.updateSoul(id, soul);
  updateAgentConfig = (id: string, configYaml: string): Promise<boolean> =>
    this.agentStore.updateConfig(id, configYaml);
  agentLogTail = (agentId: string, tail?: number) => this.agentStore.logTail(agentId, tail);
  pingIntegrations = (agentId: string): void => this.agentStore.pingIntegrations(agentId);

  // ── skills ─────────────────────────────────────────────────────────────
  toggleSkill = (agentId: string, skillId: string): void => this.skillStore.toggle(agentId, skillId);
  addSkill = (agentId: string, skill: Omit<SkillRef, 'id'>): void => this.skillStore.add(agentId, skill);
  removeSkill = (agentId: string, skillId: string): void => this.skillStore.remove(agentId, skillId);
  getSkillContent = (agentId: string, skill: SkillRef) => this.skillStore.content(agentId, skill);
  saveSkillContent = (agentId: string, skill: SkillRef, body: string): Promise<boolean> =>
    this.skillStore.saveContent(agentId, skill, body);

  // ── profile MCP servers ────────────────────────────────────────────────
  addMcp = (
    agentId: string, name: string, transport: McpServer['transport'], opts?: McpEndpointOptions,
  ): Promise<boolean> => this.agentMcpStore.add(agentId, name, transport, opts);
  updateMcp = (
    agentId: string, oldName: string, name: string, transport: McpServer['transport'],
    opts?: McpEndpointOptions,
  ): Promise<boolean> => this.agentMcpStore.update(agentId, oldName, name, transport, opts);
  setMcpEnabled = (agentId: string, serverName: string, enabled: boolean): Promise<boolean> =>
    this.agentMcpStore.setEnabled(agentId, serverName, enabled);
  connectCatalogMcp = (agentId: string, serverId: string, alias: string): Promise<boolean> =>
    this.agentMcpStore.connectCatalog(agentId, serverId, alias);
  syncCatalogMcp = (agentId: string, alias: string): Promise<boolean> =>
    this.agentMcpStore.syncCatalog(agentId, alias);
  unlinkCatalogMcp = (agentId: string, alias: string): Promise<boolean> =>
    this.agentMcpStore.unlinkCatalog(agentId, alias);
  testMcp = (agentId: string, serverName: string): Promise<boolean> =>
    this.agentMcpStore.test(agentId, serverName);
  removeMcp = (agentId: string, mcpId: string): Promise<boolean> =>
    this.agentMcpStore.remove(agentId, mcpId);

  // ── global MCP server catalog ──────────────────────────────────────────
  readonly mcpServers = this.catalogStore.servers;
  readonly mcpServersLoading = this.catalogStore.loading;
  readonly retainedMcpResources = this.catalogStore.retainedResources;
  mcpServerById = this.catalogStore.byId;
  refreshMcpServers = (silent?: boolean): Promise<void> => this.catalogStore.refresh(silent);
  refreshRetainedMcpResources = (): Promise<void> => this.catalogStore.refreshRetainedResources();
  saveCatalogMcpServer = (input: McpCatalogServerInput, id?: string): Promise<string> =>
    this.catalogStore.save(input, id);
  deleteCatalogMcpServer = (id: string): Promise<boolean> => this.catalogStore.remove(id);
  startCatalogMcpServer = (id: string): Promise<boolean> => this.catalogStore.start(id);
  stopCatalogMcpServer = (id: string): Promise<boolean> => this.catalogStore.stop(id);
  applyCatalogMcpServer = (id: string): Promise<boolean> => this.catalogStore.apply(id);
  checkCatalogMcpServer = (id: string): Promise<boolean> => this.catalogStore.check(id);
  mcpServerLogTail = (id: string, tail?: number) => this.catalogStore.logTail(id, tail);
  purgeRetainedMcpResource = (id: string): Promise<boolean> =>
    this.catalogStore.purgeRetainedResource(id);

  // ── model providers and catalogs ───────────────────────────────────────
  readonly modelProviders = this.providerStore.ollamaProviders;
  readonly llmProviders = this.providerStore.llmProviders;
  refreshModelProviders = (): Promise<void> => this.providerStore.refresh();
  refreshProviderRegistry = (): Promise<void> => this.providerStore.refreshRegistry();
  addModelProvider = (name: string, url: string): void => this.providerStore.add(name, url);
  removeModelProvider = (id: string): void => this.providerStore.remove(id);
  checkModelProvider = (id: string): void => this.providerStore.check(id);
  providerModels = (id: string) => this.providerStore.models(id);
  pullModel = (id: string, name: string): Promise<void> => this.providerStore.pullModel(id, name);
  deleteProviderModel = (id: string, name: string): Promise<void> =>
    this.providerStore.deleteModel(id, name);
  pullStatus = (id: string): Promise<ApiPullState[]> => this.providerStore.pullStatus(id);
  modelCatalog = (provider: string): Promise<string[]> => this.providerStore.modelCatalog(provider);
  modelCatalogLive = (provider: string, apiKey: string): Promise<string[]> =>
    this.providerStore.modelCatalogLive(provider, apiKey);

  // ── profile setup (.env) and sessions ──────────────────────────────────
  /** Last known setup per profile; null until one has been read. */
  agentSetupOf = (agentId: string): ApiAgentSetup | null => this.setupStore.setupOf(agentId);
  agentSetupLoading = (agentId: string): boolean => this.setupStore.isSetupLoading(agentId);
  /** Reads a profile's setup, answering the cached copy unless `force`. */
  agentSetup = (agentId: string, force?: boolean): Promise<ApiAgentSetup | null> =>
    this.setupStore.setup(agentId, force);
  setAgentEnv = (agentId: string, entries: Array<{ key: string; value: string | null }>) =>
    this.setupStore.setEnv(agentId, entries);
  initAgentEnv = (agentId: string): Promise<ApiAgentSetup | null> => this.setupStore.initEnv(agentId);
  authProviders = (containerId: string): Promise<ApiSetupAuthProvider[]> =>
    this.setupStore.authProviders(containerId);
  agentSessions = (agentId: string) => this.setupStore.sessions(agentId);
  agentSessionMessages = (agentId: string, sessionId: string) =>
    this.setupStore.sessionMessages(agentId, sessionId);
  deleteAgentSession = (agentId: string, sessionId: string): Promise<void> =>
    this.setupStore.deleteSession(agentId, sessionId);

  // ── profile templates ──────────────────────────────────────────────────
  readonly profileTemplates = this.templateStore.templates;
  templateById = this.templateStore.byId;
  refreshTemplates = (): Promise<void> => this.templateStore.refresh();
  saveTemplate = (input: ProfileTemplateInput, id?: string): Promise<string> =>
    this.templateStore.save(input, id);
  deleteTemplate = (id: string): Promise<void> => this.templateStore.remove(id);
  deployTemplate = (templateId: string, containerId: string, name: string): Promise<string> =>
    this.templateStore.deploy(templateId, containerId, name);
  captureTemplate = (agentId: string, templateName?: string): Promise<string> =>
    this.templateStore.capture(agentId, templateName);

  // ── scheduled jobs ─────────────────────────────────────────────────────
  readonly containerJobs = this.jobStore.forSelectedContainer;
  /** False when the gateway is down: hermes keeps the jobs, but nothing fires them. */
  readonly schedulerRunning = this.jobStore.schedulerRunning;
  refreshJobs = (): Promise<void> => this.jobStore.refresh();
  toggleJob = (id: string): Promise<boolean> => this.jobStore.toggle(id);
  updateJob = (id: string, patch: Partial<CronJob>): Promise<boolean> =>
    this.jobStore.update(id, patch);
  createJob = (
    containerId: string, agentId: string, name: string, schedule: string, prompt: string,
    deliverTo: string,
  ): Promise<boolean> =>
    this.jobStore.create(containerId, agentId, name, schedule, prompt, deliverTo);
  runJobNow = (id: string): Promise<boolean> => this.jobStore.runNow(id);
  removeJob = (id: string): Promise<boolean> => this.jobStore.remove(id);

  // ── board ──────────────────────────────────────────────────────────────
  readonly containerTasks = this.boardStore.forSelectedContainer;
  moveTask = (id: string, column: BoardColumn): void => this.boardStore.move(id, column);

  // ── webhooks ───────────────────────────────────────────────────────────
  readonly containerWebhooks = this.webhookStore.forSelectedContainer;
  readonly webhookListeners = this.webhookStore.containerListeners;
  refreshWebhooks = (): Promise<void> => this.webhookStore.refresh();
  webhookListenerOf = (agentId: string) => this.webhookStore.listenerOf(agentId);
  setWebhookListener = (agentId: string, enabled: boolean, port?: number): Promise<boolean> =>
    this.webhookStore.setListenerEnabled(agentId, enabled, port);
  addWebhook = (agentId: string, request: ApiSubscribeWebhookRequest): Promise<boolean> =>
    this.webhookStore.subscribe(agentId, request);
  removeWebhook = (agentId: string, route: string): Promise<boolean> =>
    this.webhookStore.remove(agentId, route);
  webhookSecret = (agentId: string, route: string): Promise<string | null> =>
    this.webhookStore.secretOf(agentId, route);
  testWebhook = (agentId: string, route: string): Promise<string | null> =>
    this.webhookStore.test(agentId, route);
}
