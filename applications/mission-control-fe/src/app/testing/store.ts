import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { MC_CONFIG, McRuntimeConfig, runtimeConfig } from '../core/app-config';
import { ApiAgentProfile, ApiContainer } from '../core/hermes-api';
import { AgentMcpStore } from '../core/store/agent-mcp-store';
import { AgentRemoval } from '../core/store/agent-removal';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { AgentSkillStore } from '../core/store/agent-skill-store';
import { AgentStore } from '../core/store/agent-store';
import { BoardStore } from '../core/store/board-store';
import { ContainerLifecycle } from '../core/store/container-lifecycle';
import { ContainerStore } from '../core/store/container-store';
import { CredentialStore } from '../core/store/credential-store';
import { HostStore } from '../core/store/host-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { JobStore } from '../core/store/job-store';
import { LiveSync } from '../core/store/live-sync';
import { LogStore } from '../core/store/log-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { McpGroupStore } from '../core/store/mcp-group-store';
import { PromptGroupStore } from '../core/store/prompt-group-store';
import { PromptStore } from '../core/store/prompt-store';
import { SkillGroupStore } from '../core/store/skill-group-store';
import { SkillGuideStore } from '../core/store/skill-guide-store';
import { SkillStore } from '../core/store/skill-store';
import { InferenceEndpointStore } from '../core/store/inference-endpoint-store';
import { ProviderStore } from '../core/store/provider-store';
import { ActivityStore } from '../core/store/activity-store';
import { StoreContext } from '../core/store/store-context';
import { TemplateStore } from '../core/store/template-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { WebhookStore } from '../core/store/webhook-store';

/**
 * Fixtures for the store slices. Loading is {@link LiveSync}'s job, so a slice
 * test starts from inventory already in place and is about what happens next.
 *
 * Test-only: excluded from the app build (tsconfig.app.json) and from coverage.
 */

/** Same-origin and a local socket, which is what a spec gets unless it sets
 *  `window.__MC_CONFIG__` to say otherwise. */
const TEST_CONFIG: McRuntimeConfig = {
  apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock',
};

export const specConfig = (): McRuntimeConfig =>
  typeof window !== 'undefined' && window.__MC_CONFIG__ ? runtimeConfig() : TEST_CONFIG;

/** Replaces the backend of a built context. `api` is the one seam every slice
 *  shares, so substituting it answers the whole store at once; a stub only
 *  carries the calls a test actually reaches. */
export const stubBackend = (ctx: StoreContext, api: unknown): void => {
  (ctx as unknown as { api: unknown }).api = api;
};

/** The slices, with `api` already stubbed — how a slice spec starts. */
export const testSlices = (api: unknown = {}): StoreSlices => {
  const slices = storeSlices();
  stubBackend(slices.ctx, api);
  return slices;
};

/** One container as the backend reports it. */
export const apiContainer = (patch: Partial<ApiContainer> = {}): ApiContainer => ({
  id: 'c-1', shortId: 'aa11bb2', name: 'hermes-prod', hostId: 'dh-local', status: 'running',
  image: 'nousresearch/hermes-agent', version: 'v2026.8.3', imageDigest: null,
  startedAt: 10, sizeRootFsGb: 2,
  profiles: ['atlas'], ...patch,
});

/** One profile as the backend reports it. */
export const apiProfile = (name: string, patch: Partial<ApiAgentProfile> = {}): ApiAgentProfile => ({
  id: `a-${name}`, containerId: 'c-1', name, role: 'Ops', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…9f2c', cwd: '/opt/data',
  soul: '# SOUL', memoryMd: '# MEMORY', configYaml: 'provider: anthropic',
  skills: [], mcp: [], integrations: [], lastActive: 20,
  gateway: {
    state: 'running', desiredState: 'running', activeAgents: 0, agentVersion: '0.20.5',
    sessionStore: 'ok', paused: false, pauseReason: null,
  },
  ...patch,
});

/** The api stub shape a slice fixture accepts: the resource clients it reaches. */
type ApiStub = Record<string, Record<string, unknown> | undefined>;

/**
 * Context plus the container and agent slices every profile-scoped store depends
 * on, already loaded and with the first container selected. `api` fills in the
 * resource clients the slice under test reaches; `containers.list` and
 * `agents.list` default to the fixtures above.
 */
export const loadedAgentSlices = async (
  api: ApiStub = {},
  { containers = [apiContainer()], profiles = [apiProfile('atlas')] } = {},
): Promise<StoreSlices> => {
  const slices = testSlices({
    ...api,
    containers: { list: vi.fn().mockResolvedValue(containers), ...api['containers'] },
    agents: { list: vi.fn().mockResolvedValue(profiles), ...api['agents'] },
  });
  await slices.containers.refresh();
  await slices.agents.refresh();
  if (containers.length) slices.containers.select(containers[0].id);
  return slices;
};

/**
 * Every slice, built by the application's own DI graph rather than by hand — so
 * a spec that reaches across slices exercises the same wiring the app boots
 * with, and a missing provider or a construction cycle fails here too.
 *
 * The module is reset first: the slices are root-provided singletons, and a
 * spec that starts from an empty store must not inherit the previous one's.
 */
export const storeSlices = () => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: MC_CONFIG, useValue: specConfig() }] });
  // built on first use, not up front: a slice that subscribes to container
  // selection must not exist before a fixture has finished loading one, or it
  // would answer the poll that loading triggers instead of the test's own call
  return {
    get ctx() { return TestBed.inject(StoreContext); },
    get activity() { return TestBed.inject(ActivityStore); },
    get hosts() { return TestBed.inject(HostStore); },
    get containers() { return TestBed.inject(ContainerStore); },
    get lifecycle() { return TestBed.inject(ContainerLifecycle); },
    get credentials() { return TestBed.inject(CredentialStore); },
    get logs() { return TestBed.inject(LogStore); },
    get images() { return TestBed.inject(ImageCatalogStore); },
    get agents() { return TestBed.inject(AgentStore); },
    get removal() { return TestBed.inject(AgentRemoval); },
    get skills() { return TestBed.inject(AgentSkillStore); },
    get agentMcp() { return TestBed.inject(AgentMcpStore); },
    get catalog() { return TestBed.inject(McpCatalogStore); },
    get prompts() { return TestBed.inject(PromptStore); },
    get promptGroups() { return TestBed.inject(PromptGroupStore); },
    get mcpGroups() { return TestBed.inject(McpGroupStore); },
    // `skills` is the per-agent slice; the library is `skillLibrary`
    get skillLibrary() { return TestBed.inject(SkillStore); },
    get guides() { return TestBed.inject(SkillGuideStore); },
    get skillGroups() { return TestBed.inject(SkillGroupStore); },
    get providers() { return TestBed.inject(ProviderStore); },
    get endpoints() { return TestBed.inject(InferenceEndpointStore); },
    get setup() { return TestBed.inject(AgentSetupStore); },
    get templates() { return TestBed.inject(TemplateStore); },
    get jobs() { return TestBed.inject(JobStore); },
    get board() { return TestBed.inject(BoardStore); },
    get webhooks() { return TestBed.inject(WebhookStore); },
    get terminal() { return TestBed.inject(TerminalRequestStore); },
    get liveSync() { return TestBed.inject(LiveSync); },
  };
};

export type StoreSlices = ReturnType<typeof storeSlices>;

/** Lets a promise chain nothing awaits run to completion, under real or fake
 *  timers — a plain `setTimeout` would never fire while timers are faked. */
export const flush = async (): Promise<void> => {
  if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
  else await new Promise(resolve => setTimeout(resolve, 0));
};

/**
 * The newest message of each kind still on the toast stack.
 *
 * <p>Here rather than on {@link StoreContext}: the app renders the whole stack, so a
 * newest-of-kind reader had no production caller — every one of them was an assertion.
 */
const newest = (ctx: StoreContext, kind: 'ok' | 'error'): string | null => {
  const matching = ctx.toasts().filter(t => t.kind === kind);
  return matching.length ? matching[matching.length - 1].message : null;
};

/** The newest failure still on screen. */
export const liveError = (ctx: StoreContext): string | null => newest(ctx, 'error');

/** The newest confirmation still on screen. */
export const liveNotice = (ctx: StoreContext): string | null => newest(ctx, 'ok');

/**
 * DI providers for a hand-built store stub, keyed by slice name.
 *
 * <p>Twenty-one specs wrote out the same slice-name-to-token table, four to ten entries at a
 * time, so adding a slice meant editing every spec that already stubbed a neighbour of it. The
 * stub objects themselves stay in their specs — those are each test's actual input — but which
 * token a slice is injected under is one fact, and it lives here.
 *
 * <p>Only recognised keys are provided, so a spec can keep its own bookkeeping — a signal it
 * replays a poll through — in the same object as its slices, and still stub exactly what its
 * component reaches for and nothing more. A misspelled slice name surfaces as Angular's own
 * "No provider for XStore" at {@code createComponent}, which names the missing token.
 */
const SLICE_TOKENS = {
  agentMcp: AgentMcpStore,
  agents: AgentStore,
  board: BoardStore,
  catalog: McpCatalogStore,
  containers: ContainerStore,
  credentials: CredentialStore,
  ctx: StoreContext,
  hosts: HostStore,
  images: ImageCatalogStore,
  jobs: JobStore,
  lifecycle: ContainerLifecycle,
  liveSync: LiveSync,
  logs: LogStore,
  providers: ProviderStore,
  endpoints: InferenceEndpointStore,
  removal: AgentRemoval,
  setup: AgentSetupStore,
  skills: AgentSkillStore,
  skillLibrary: SkillStore,
  guides: SkillGuideStore,
  skillGroups: SkillGroupStore,
  promptGroups: PromptGroupStore,
  mcpGroups: McpGroupStore,
  templates: TemplateStore,
  terminal: TerminalRequestStore,
  webhooks: WebhookStore,
} as const;

export type StoreSliceName = keyof typeof SLICE_TOKENS;

export function provideStores(
  stub: Partial<Record<StoreSliceName, unknown>>,
): { provide: unknown; useValue: unknown }[] {
  return Object.entries(stub)
    .filter(([slice]) => slice in SLICE_TOKENS)
    .map(([slice, value]) => ({ provide: SLICE_TOKENS[slice as StoreSliceName], useValue: value }));
}
