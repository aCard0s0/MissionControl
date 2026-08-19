import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HermesStore } from './hermes-store';

/**
 * The facade is ~80 one-line delegates onto the slices under ./store. They all
 * look alike, so the failure mode is a mis-wired one — `removeWebhook` reaching
 * `subscribe`, a renamed slice method left behind — which no page-level test
 * would catch, because both sides still typecheck.
 *
 * Each slice is therefore wrapped in a recorder and every delegate is called
 * once: the table below is the wiring itself, in one readable place. What the
 * slices then do is their own specs' subject.
 */
interface Call {
  slice: string;
  method: string;
  args: readonly unknown[];
}

/** The slice fields the delegates reach through, by their property names. */
const SLICES = [
  'hostStore', 'containerStore', 'logStore', 'agentStore', 'skillStore', 'catalogStore',
  'agentMcpStore', 'setupStore', 'providerStore', 'imageStore', 'templateStore', 'jobStore',
  'boardStore', 'webhookStore', 'lifecycle', 'terminal', 'liveSync',
] as const;

const ANSWER = Symbol('slice answer');

/** A store whose slices record what the facade asks of them. */
const recorded = () => {
  const store = new HermesStore();
  const calls: Call[] = [];
  for (const name of SLICES) {
    const slice = (store as unknown as Record<string, object>)[name];
    (store as unknown as Record<string, object>)[name] = new Proxy(slice, {
      get(target, prop) {
        const value = Reflect.get(target, prop);
        if (typeof value !== 'function') return value;
        return (...args: unknown[]) => {
          calls.push({ slice: name, method: String(prop), args });
          return ANSWER;
        };
      },
    });
  }
  return { store, calls };
};

/** `call` runs the delegate; it must reach exactly `slice.method(...args)`. */
interface Wiring {
  readonly call: (store: HermesStore) => unknown;
  readonly slice: typeof SLICES[number];
  readonly method: string;
  readonly args?: readonly unknown[];
}

const SKILL = { id: 's-1', name: 'ops', source: 'bundled' as const, version: '1', description: '', enabled: true };

const wiring: Record<string, Wiring> = {
  // ── docker hosts ───────────────────────────────────────────────────────
  addDockerHost: {
    call: s => s.addDockerHost('edge', 'tcp://edge:2375'), slice: 'hostStore', method: 'add',
    args: ['edge', 'tcp://edge:2375'],
  },
  removeDockerHost: { call: s => s.removeDockerHost('dh-1'), slice: 'hostStore', method: 'remove', args: ['dh-1'] },
  checkDockerHost: { call: s => s.checkDockerHost('dh-1'), slice: 'hostStore', method: 'check', args: ['dh-1'] },

  // ── containers ─────────────────────────────────────────────────────────
  selectContainer: { call: s => s.selectContainer('c-1'), slice: 'containerStore', method: 'select', args: ['c-1'] },
  deployContainer: {
    call: s => s.deployContainer('hermes-lab', 'v1', ['ops'], 'dh-local'), slice: 'lifecycle',
    method: 'deploy', args: ['hermes-lab', 'v1', ['ops'], 'dh-local'],
  },
  setContainerStatus: {
    call: s => s.setContainerStatus('c-1', 'running'), slice: 'lifecycle', method: 'setStatus',
    args: ['c-1', 'running'],
  },
  updateContainer: {
    call: s => s.updateContainer('c-1', 'v2'), slice: 'lifecycle', method: 'update', args: ['c-1', 'v2'],
  },
  removeContainer: { call: s => s.removeContainer('c-1'), slice: 'lifecycle', method: 'remove', args: ['c-1'] },
  refreshLogs: { call: s => s.refreshLogs(), slice: 'logStore', method: 'refresh', args: [] },

  // ── images ─────────────────────────────────────────────────────────────
  imageTags: { call: s => s.imageTags('dh-1'), slice: 'imageStore', method: 'tags', args: ['dh-1'] },
  refreshImageCatalog: {
    call: s => s.refreshImageCatalog('dh-1', true), slice: 'imageStore', method: 'refresh',
    args: ['dh-1', true],
  },
  refreshImageCatalogs: {
    call: s => s.refreshImageCatalogs(true), slice: 'imageStore', method: 'refreshAll', args: [true],
  },

  // ── profiles ───────────────────────────────────────────────────────────
  createAgent: {
    call: s => s.createAgent('c-1', 'atlas', 'anthropic', 'm', 'sk-x', 'a-clone', 'http://b', 'pt-1'),
    slice: 'agentStore', method: 'create',
    args: ['c-1', 'atlas', 'anthropic', 'm', 'sk-x', 'a-clone', 'http://b', 'pt-1', undefined],
  },
  removeAgent: {
    call: s => s.removeAgent('a-1'), slice: 'agentStore', method: 'remove',
    args: ['a-1', expect.any(Function)],
  },
  updateSoul: {
    call: s => s.updateSoul('a-1', '# SOUL'), slice: 'agentStore', method: 'updateSoul',
    args: ['a-1', '# SOUL'],
  },
  updateAgentConfig: {
    call: s => s.updateAgentConfig('a-1', 'p: x'), slice: 'agentStore', method: 'updateConfig',
    args: ['a-1', 'p: x'],
  },
  agentLogTail: {
    call: s => s.agentLogTail('a-1', 50), slice: 'agentStore', method: 'logTail', args: ['a-1', 50],
  },
  pingIntegrations: {
    call: s => s.pingIntegrations('a-1'), slice: 'agentStore', method: 'pingIntegrations', args: ['a-1'],
  },

  // ── skills ─────────────────────────────────────────────────────────────
  toggleSkill: {
    call: s => s.toggleSkill('a-1', 's-1'), slice: 'skillStore', method: 'toggle', args: ['a-1', 's-1'],
  },
  addSkill: {
    call: s => s.addSkill('a-1', SKILL), slice: 'skillStore', method: 'add', args: ['a-1', SKILL],
  },
  removeSkill: {
    call: s => s.removeSkill('a-1', 's-1'), slice: 'skillStore', method: 'remove', args: ['a-1', 's-1'],
  },
  getSkillContent: {
    call: s => s.getSkillContent('a-1', SKILL), slice: 'skillStore', method: 'content', args: ['a-1', SKILL],
  },
  saveSkillContent: {
    call: s => s.saveSkillContent('a-1', SKILL, '# body'), slice: 'skillStore', method: 'saveContent',
    args: ['a-1', SKILL, '# body'],
  },

  // ── profile MCP servers ────────────────────────────────────────────────
  addMcp: {
    call: s => s.addMcp('a-1', 'github', 'http', { url: 'http://gh' }), slice: 'agentMcpStore',
    method: 'add', args: ['a-1', 'github', 'http', { url: 'http://gh' }],
  },
  updateMcp: {
    call: s => s.updateMcp('a-1', 'gh', 'github', 'http'), slice: 'agentMcpStore', method: 'update',
    args: ['a-1', 'gh', 'github', 'http', undefined],
  },
  setMcpEnabled: {
    call: s => s.setMcpEnabled('a-1', 'github', false), slice: 'agentMcpStore', method: 'setEnabled',
    args: ['a-1', 'github', false],
  },
  connectCatalogMcp: {
    call: s => s.connectCatalogMcp('a-1', 'mcp-1', 'browser'), slice: 'agentMcpStore',
    method: 'connectCatalog', args: ['a-1', 'mcp-1', 'browser'],
  },
  syncCatalogMcp: {
    call: s => s.syncCatalogMcp('a-1', 'browser'), slice: 'agentMcpStore', method: 'syncCatalog',
    args: ['a-1', 'browser'],
  },
  unlinkCatalogMcp: {
    call: s => s.unlinkCatalogMcp('a-1', 'browser'), slice: 'agentMcpStore', method: 'unlinkCatalog',
    args: ['a-1', 'browser'],
  },
  testMcp: {
    call: s => s.testMcp('a-1', 'github'), slice: 'agentMcpStore', method: 'test', args: ['a-1', 'github'],
  },
  removeMcp: {
    call: s => s.removeMcp('a-1', 'm-1'), slice: 'agentMcpStore', method: 'remove', args: ['a-1', 'm-1'],
  },

  // ── MCP catalog ────────────────────────────────────────────────────────
  refreshMcpServers: {
    call: s => s.refreshMcpServers(true), slice: 'catalogStore', method: 'refresh', args: [true],
  },
  refreshRetainedMcpResources: {
    call: s => s.refreshRetainedMcpResources(), slice: 'catalogStore', method: 'refreshRetainedResources',
    args: [],
  },
  saveCatalogMcpServer: {
    call: s => s.saveCatalogMcpServer({ name: 'browser' } as never, 'mcp-1'), slice: 'catalogStore',
    method: 'save', args: [{ name: 'browser' }, 'mcp-1'],
  },
  deleteCatalogMcpServer: {
    call: s => s.deleteCatalogMcpServer('mcp-1'), slice: 'catalogStore', method: 'remove', args: ['mcp-1'],
  },
  startCatalogMcpServer: {
    call: s => s.startCatalogMcpServer('mcp-1'), slice: 'catalogStore', method: 'start', args: ['mcp-1'],
  },
  stopCatalogMcpServer: {
    call: s => s.stopCatalogMcpServer('mcp-1'), slice: 'catalogStore', method: 'stop', args: ['mcp-1'],
  },
  applyCatalogMcpServer: {
    call: s => s.applyCatalogMcpServer('mcp-1'), slice: 'catalogStore', method: 'apply', args: ['mcp-1'],
  },
  checkCatalogMcpServer: {
    call: s => s.checkCatalogMcpServer('mcp-1'), slice: 'catalogStore', method: 'check', args: ['mcp-1'],
  },
  mcpServerLogTail: {
    call: s => s.mcpServerLogTail('mcp-1', 40), slice: 'catalogStore', method: 'logTail', args: ['mcp-1', 40],
  },
  purgeRetainedMcpResource: {
    call: s => s.purgeRetainedMcpResource('vol-1'), slice: 'catalogStore', method: 'purgeRetainedResource',
    args: ['vol-1'],
  },

  // ── providers ──────────────────────────────────────────────────────────
  refreshModelProviders: {
    call: s => s.refreshModelProviders(), slice: 'providerStore', method: 'refresh', args: [],
  },
  refreshProviderRegistry: {
    call: s => s.refreshProviderRegistry(), slice: 'providerStore', method: 'refreshRegistry', args: [],
  },
  addModelProvider: {
    call: s => s.addModelProvider('lab', 'http://o:11434'), slice: 'providerStore', method: 'add',
    args: ['lab', 'http://o:11434'],
  },
  removeModelProvider: {
    call: s => s.removeModelProvider('mp-1'), slice: 'providerStore', method: 'remove', args: ['mp-1'],
  },
  checkModelProvider: {
    call: s => s.checkModelProvider('mp-1'), slice: 'providerStore', method: 'check', args: ['mp-1'],
  },
  providerModels: {
    call: s => s.providerModels('mp-1'), slice: 'providerStore', method: 'models', args: ['mp-1'],
  },
  pullModel: {
    call: s => s.pullModel('mp-1', 'llama3'), slice: 'providerStore', method: 'pullModel',
    args: ['mp-1', 'llama3'],
  },
  deleteProviderModel: {
    call: s => s.deleteProviderModel('mp-1', 'llama3'), slice: 'providerStore', method: 'deleteModel',
    args: ['mp-1', 'llama3'],
  },
  pullStatus: {
    call: s => s.pullStatus('mp-1'), slice: 'providerStore', method: 'pullStatus', args: ['mp-1'],
  },
  modelCatalog: {
    call: s => s.modelCatalog('anthropic'), slice: 'providerStore', method: 'modelCatalog',
    args: ['anthropic'],
  },
  modelCatalogLive: {
    call: s => s.modelCatalogLive('anthropic', 'sk-x'), slice: 'providerStore', method: 'modelCatalogLive',
    args: ['anthropic', 'sk-x'],
  },

  // ── setup and sessions ─────────────────────────────────────────────────
  agentSetupOf: { call: s => s.agentSetupOf('a-1'), slice: 'setupStore', method: 'setupOf', args: ['a-1'] },
  agentSetupLoading: {
    call: s => s.agentSetupLoading('a-1'), slice: 'setupStore', method: 'isSetupLoading', args: ['a-1'],
  },
  agentSetup: {
    call: s => s.agentSetup('a-1', true), slice: 'setupStore', method: 'setup', args: ['a-1', true],
  },
  setAgentEnv: {
    call: s => s.setAgentEnv('a-1', [{ key: 'K', value: null }]), slice: 'setupStore', method: 'setEnv',
    args: ['a-1', [{ key: 'K', value: null }]],
  },
  initAgentEnv: { call: s => s.initAgentEnv('a-1'), slice: 'setupStore', method: 'initEnv', args: ['a-1'] },
  authProviders: {
    call: s => s.authProviders('c-1'), slice: 'setupStore', method: 'authProviders', args: ['c-1'],
  },
  agentSessions: {
    call: s => s.agentSessions('a-1'), slice: 'setupStore', method: 'sessions', args: ['a-1'],
  },
  agentSessionMessages: {
    call: s => s.agentSessionMessages('a-1', 'sess-1'), slice: 'setupStore', method: 'sessionMessages',
    args: ['a-1', 'sess-1'],
  },
  deleteAgentSession: {
    call: s => s.deleteAgentSession('a-1', 'sess-1'), slice: 'setupStore', method: 'deleteSession',
    args: ['a-1', 'sess-1'],
  },

  // ── templates ──────────────────────────────────────────────────────────
  refreshTemplates: { call: s => s.refreshTemplates(), slice: 'templateStore', method: 'refresh', args: [] },
  saveTemplate: {
    call: s => s.saveTemplate({ name: 'ops' } as never, 'pt-1'), slice: 'templateStore', method: 'save',
    args: [{ name: 'ops' }, 'pt-1'],
  },
  deleteTemplate: {
    call: s => s.deleteTemplate('pt-1'), slice: 'templateStore', method: 'remove', args: ['pt-1'],
  },
  deployTemplate: {
    call: s => s.deployTemplate('pt-1', 'c-1', 'sre'), slice: 'templateStore', method: 'deploy',
    args: ['pt-1', 'c-1', 'sre'],
  },
  captureTemplate: {
    call: s => s.captureTemplate('a-1', 'atlas-template'), slice: 'templateStore', method: 'capture',
    args: ['a-1', 'atlas-template'],
  },

  // ── jobs ───────────────────────────────────────────────────────────────
  refreshJobs: { call: s => s.refreshJobs(), slice: 'jobStore', method: 'refresh', args: [] },
  toggleJob: { call: s => s.toggleJob('j-1'), slice: 'jobStore', method: 'toggle', args: ['j-1'] },
  updateJob: {
    call: s => s.updateJob('j-1', { name: 'digest' }), slice: 'jobStore', method: 'update',
    args: ['j-1', { name: 'digest' }],
  },
  createJob: {
    call: s => s.createJob('c-1', 'a-1', 'digest', '@daily', 'go', 'log'), slice: 'jobStore',
    method: 'create', args: ['c-1', 'a-1', 'digest', '@daily', 'go', 'log'],
  },
  runJobNow: { call: s => s.runJobNow('j-1'), slice: 'jobStore', method: 'runNow', args: ['j-1'] },
  removeJob: { call: s => s.removeJob('j-1'), slice: 'jobStore', method: 'remove', args: ['j-1'] },

  // ── board ──────────────────────────────────────────────────────────────
  moveTask: {
    call: s => s.moveTask('t-1', 'done'), slice: 'boardStore', method: 'move', args: ['t-1', 'done'],
  },

  // ── webhooks ───────────────────────────────────────────────────────────
  refreshWebhooks: { call: s => s.refreshWebhooks(), slice: 'webhookStore', method: 'refresh', args: [] },
  webhookListenerOf: {
    call: s => s.webhookListenerOf('a-1'), slice: 'webhookStore', method: 'listenerOf', args: ['a-1'],
  },
  setWebhookListener: {
    call: s => s.setWebhookListener('a-1', true, 8644), slice: 'webhookStore', method: 'setListenerEnabled',
    args: ['a-1', true, 8644],
  },
  addWebhook: {
    call: s => s.addWebhook('a-1', { name: 'alerts' }), slice: 'webhookStore', method: 'subscribe',
    args: ['a-1', { name: 'alerts' }],
  },
  removeWebhook: {
    call: s => s.removeWebhook('a-1', 'alerts'), slice: 'webhookStore', method: 'remove',
    args: ['a-1', 'alerts'],
  },
  webhookSecret: {
    call: s => s.webhookSecret('a-1', 'alerts'), slice: 'webhookStore', method: 'secretOf',
    args: ['a-1', 'alerts'],
  },
  testWebhook: {
    call: s => s.testWebhook('a-1', 'alerts'), slice: 'webhookStore', method: 'test',
    args: ['a-1', 'alerts'],
  },
};

describe('HermesStore facade wiring', () => {
  beforeEach(() => {
    window.__MC_CONFIG__ = { apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' };
    // the constructor probes the backend; nothing here is about that probe
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{}')));
  });

  afterEach(() => vi.unstubAllGlobals());

  for (const [name, spec] of Object.entries(wiring)) {
    it(`${name} → ${spec.slice}.${spec.method}`, () => {
      const { store, calls } = recorded();

      spec.call(store);

      expect(calls).toEqual([{ slice: spec.slice, method: spec.method, args: spec.args }]);
    });
  }

  it('answers what the slice answered, rather than swallowing it', () => {
    const { store } = recorded();

    expect(store.removeJob('j-1')).toBe(ANSWER);
  });

  it('drops everything keyed to a profile when it is removed, not just the profile', () => {
    const { store, calls } = recorded();

    store.removeAgent('a-1');
    (calls[0].args[1] as (id: string) => void)('a-1');

    expect(calls.slice(1)).toEqual([
      { slice: 'jobStore', method: 'dropByAgent', args: ['a-1'] },
      { slice: 'boardStore', method: 'dropByAgent', args: ['a-1'] },
      { slice: 'webhookStore', method: 'dropByAgent', args: ['a-1'] },
      { slice: 'setupStore', method: 'forget', args: ['a-1'] },
    ]);
  });

  it('toasts through the shared context, so every slice reports the same way', () => {
    const { store } = recorded();

    store.toast('deploy failed: name already in use');

    expect(store.liveError()).toBe('deploy failed: name already in use');
  });

  it('exposes each slice\'s state as a signal the pages can read', () => {
    const { store } = recorded();

    expect(store.containers()).toEqual([]);
    expect(store.mcpServers()).toEqual([]);
    expect(store.profileTemplates()).toEqual([]);
    expect(store.containerJobs()).toEqual([]);
    expect(store.containerTasks()).toEqual([]);
    expect(store.containerWebhooks()).toEqual([]);
    expect(store.backendStatus()).toBe('connecting');
  });
});
