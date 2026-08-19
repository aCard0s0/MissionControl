import { vi } from 'vitest';
import { ApiAgentProfile, ApiContainer } from '../core/hermes-api';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { StoreContext } from '../core/store/store-context';

/**
 * Fixtures for the store slices. Loading is {@link LiveSync}'s job, so a slice
 * test starts from inventory already in place and is about what happens next.
 *
 * Test-only: excluded from the app build (tsconfig.app.json) and from coverage.
 */

/**
 * A {@link StoreContext} whose backend is a stub. `api` is the one seam every
 * slice shares, so substituting it here answers the whole store at once; the
 * stub only carries the calls a test actually reaches.
 */
export const testContext = (api: unknown = {}): StoreContext => {
  const ctx = new StoreContext({ apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' });
  (ctx as unknown as { api: unknown }).api = api;
  return ctx;
};

/** Replaces the backend of an already-built context. */
export const stubBackend = (ctx: StoreContext, api: unknown): void => {
  (ctx as unknown as { api: unknown }).api = api;
};

/** One container as the backend reports it. */
export const apiContainer = (patch: Partial<ApiContainer> = {}): ApiContainer => ({
  id: 'c-1', shortId: 'aa11bb2', name: 'hermes-prod', hostId: 'dh-local', status: 'running',
  image: 'nousresearch/hermes-agent', version: 'v2026.8.3', startedAt: 10, sizeRootFsGb: 2,
  profiles: ['atlas'], ...patch,
});

/** One profile as the backend reports it. */
export const apiProfile = (name: string, patch: Partial<ApiAgentProfile> = {}): ApiAgentProfile => ({
  id: `a-${name}`, containerId: 'c-1', name, role: 'Ops', state: 'idle',
  provider: 'anthropic', model: 'claude-fable-5', apiKeyMasked: '…9f2c', cwd: '/opt/data',
  soul: '# SOUL', memoryMd: '# MEMORY', configYaml: 'provider: anthropic',
  skills: [], mcp: [], integrations: [], lastActive: 20, ...patch,
});

/** The api stub shape a slice fixture accepts: the resource clients it reaches. */
type ApiStub = Record<string, Record<string, unknown> | undefined>;

export interface AgentSlices {
  ctx: StoreContext;
  containers: ContainerStore;
  agents: AgentStore;
}

/**
 * Context plus the container and agent slices every profile-scoped store depends
 * on, already loaded and with the first container selected. `api` fills in the
 * resource clients the slice under test reaches; `containers.list` and
 * `agents.list` default to the fixtures above.
 */
export const loadedAgentSlices = async (
  api: ApiStub = {},
  { containers = [apiContainer()], profiles = [apiProfile('atlas')] } = {},
): Promise<AgentSlices> => {
  const ctx = testContext();
  const containerStore = new ContainerStore(ctx);
  const agentStore = new AgentStore(ctx, containerStore);
  stubBackend(ctx, {
    ...api,
    containers: { list: vi.fn().mockResolvedValue(containers), ...api['containers'] },
    agents: { list: vi.fn().mockResolvedValue(profiles), ...api['agents'] },
  });
  await containerStore.refresh();
  await agentStore.refresh();
  if (containers.length) containerStore.select(containers[0].id);
  return { ctx, containers: containerStore, agents: agentStore };
};

/** Lets a promise chain nothing awaits run to completion, under real or fake
 *  timers — a plain `setTimeout` would never fire while timers are faked. */
export const flush = async (): Promise<void> => {
  if (vi.isFakeTimers()) await vi.advanceTimersByTimeAsync(0);
  else await new Promise(resolve => setTimeout(resolve, 0));
};
