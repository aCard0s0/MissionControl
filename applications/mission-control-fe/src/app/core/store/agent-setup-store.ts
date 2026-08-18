import { signal } from '@angular/core';
import { ApiAgentSetup, ApiSetupAuthProvider } from '../hermes-api';
import { AgentProfile, ChatMessage, SessionInfo } from '../models';
import { buildMockChat } from '../mock-data';
import { maskTail } from '../../shared/secret';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { MOCK_SETUP_API_KEYS, MOCK_SETUP_MESSAGING } from './mock-catalogs';
import { StoreContext } from './store-context';

/**
 * A profile's credentials (its `.env`) and its recorded chat sessions — the two
 * read-mostly surfaces the detail page's Setup and Sessions tabs live on. Both
 * answer `null` when the profile or its container is unknown, which the page
 * renders as "nothing loaded" rather than as an error.
 *
 * Setup is cached per profile: reading it runs `hermes status` inside the
 * container, which takes seconds, so re-entering the tab must not pay for it
 * again. Every write answers with the refreshed setup and replaces the entry, and
 * the Setup tab's refresh button forces a re-read.
 */
export class AgentSetupStore {
  /** Last known setup per agent id — the Setup tab renders straight off this. */
  readonly setups = signal<Record<string, ApiAgentSetup>>({});

  /** Agent ids with a setup read in flight, so two views cannot both fetch. */
  readonly setupLoading = signal<ReadonlySet<string>>(new Set());

  /** Mock-mode .env contents per agent; presence of a key = file exists. */
  private readonly mockEnv = new Map<string, Record<string, string>>();

  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
  ) {}

  /** The cached setup for a profile, or null if it has never been read. */
  setupOf(agentId: string): ApiAgentSetup | null {
    return this.setups()[agentId] ?? null;
  }

  isSetupLoading(agentId: string): boolean {
    return this.setupLoading().has(agentId);
  }

  /**
   * Reads a profile's setup, answering the cached copy unless `force`. Returns
   * null when the read failed or the profile is unknown; the cached copy, if any,
   * is left in place so a failed refresh does not blank the tab.
   */
  async setup(agentId: string, force = false): Promise<ApiAgentSetup | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return null;
    const cached = this.setupOf(agentId);
    if (cached && !force) return cached;
    if (this.isSetupLoading(agentId)) return cached;

    this.markLoading(agentId, true);
    try {
      if (!this.ctx.mock) {
        const resolved = this.agents.resolve(agentId);
        if (!resolved) return null;
        try {
          return this.remember(agentId, await this.ctx.api.agents.setup(resolved.ref));
        } catch (e) {
          this.ctx.toastFailure('setup load', e);
          return null;
        }
      }
      return this.remember(agentId, this.buildMockSetup(agent));
    } finally {
      this.markLoading(agentId, false);
    }
  }

  /** Empty/null entry value removes that key from the .env file. */
  setEnv(agentId: string, entries: Array<{ key: string; value: string | null }>): Promise<ApiAgentSetup | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return Promise.resolve(null);
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return Promise.resolve(null);
      return this.ctx.api.agents.setEnv(resolved.ref, entries)
        .then(setup => this.remember(agentId, setup))
        .catch(e => {
          this.ctx.toastFailure('env save', e);
          return null;
        });
    }
    const env = { ...(this.mockEnv.get(agent.id) ?? {}) };
    for (const { key, value } of entries) {
      if (value) env[key] = value;
      else delete env[key];
    }
    this.mockEnv.set(agent.id, env);
    return Promise.resolve(this.remember(agentId, this.buildMockSetup(agent)));
  }

  /** Writes the commented-out .env template only when the file is missing. */
  initEnv(agentId: string): Promise<ApiAgentSetup | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return Promise.resolve(null);
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return Promise.resolve(null);
      return this.ctx.api.agents.initEnv(resolved.ref)
        .then(setup => this.remember(agentId, setup))
        .catch(e => {
          this.ctx.toastFailure('env init', e);
          return null;
        });
    }
    if (!this.mockEnv.has(agent.id)) this.mockEnv.set(agent.id, {});
    return Promise.resolve(this.remember(agentId, this.buildMockSetup(agent)));
  }

  /** Container-level auth-provider status (Nous Portal OAuth etc.) for the create
   *  modal — readable before an agent exists. Failures degrade to an empty list
   *  so the modal still works without the status badge. */
  authProviders(containerId: string): Promise<ApiSetupAuthProvider[]> {
    const container = this.containers.byId(containerId);
    if (!container) return Promise.resolve([]);
    if (!this.ctx.mock) {
      return this.ctx.api.agents.authProviders(container.hostId, containerId).catch(() => []);
    }
    return Promise.resolve([
      { label: 'Nous Portal', ok: false, status: 'not logged in (run: hermes portal)', hint: 'hermes portal' },
      { label: 'OpenAI Codex', ok: true, status: 'logged in', hint: null },
    ]);
  }

  /** Lists this agent's recorded sessions (mock returns the seeded list). */
  sessions(agentId: string): Promise<SessionInfo[] | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return Promise.resolve(null);
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return Promise.resolve(null);
      return this.ctx.api.agents.sessions(resolved.ref)
        .then(list => list.map(s => ({
          id: s.id, title: s.title, platform: s.platform,
          startedAt: s.startedAt, messages: s.messages,
          status: s.status === 'open' ? 'open' as const : 'closed' as const,
        })))
        .catch(e => { this.ctx.toastFailure('sessions load', e); return null; });
    }
    return Promise.resolve(agent.sessions.map(s => ({ ...s })));
  }

  /** Chat history (messages) for a single session. */
  sessionMessages(agentId: string, sessionId: string): Promise<ChatMessage[] | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return Promise.resolve(null);
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return Promise.resolve(null);
      return this.ctx.api.agents.sessionMessages(resolved.ref, sessionId)
        .catch(e => { this.ctx.toastFailure('session load', e); return null; });
    }
    const session = agent.sessions.find(x => x.id === sessionId);
    if (!session) return Promise.resolve(null);
    return Promise.resolve(buildMockChat(session));
  }

  /** Deletes a session file; mock removes it from the in-memory list. */
  deleteSession(agentId: string, sessionId: string): Promise<void> {
    const agent = this.agents.byId(agentId);
    if (!agent) return Promise.resolve();
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return Promise.resolve();
      return this.ctx.api.agents.deleteSession(resolved.ref, sessionId);
    }
    this.agents.update(agentId, x => ({
      ...x, sessions: x.sessions.filter(s => s.id !== sessionId),
    }));
    return Promise.resolve();
  }

  /** Drops a profile's cached setup — its credentials are gone with it. */
  forget(agentId: string): void {
    this.setups.update(all => {
      if (!(agentId in all)) return all;
      const next = { ...all };
      delete next[agentId];
      return next;
    });
  }

  private remember(agentId: string, setup: ApiAgentSetup): ApiAgentSetup {
    this.setups.update(all => ({ ...all, [agentId]: setup }));
    return setup;
  }

  private markLoading(agentId: string, loading: boolean): void {
    this.setupLoading.update(ids => {
      const next = new Set(ids);
      if (loading) next.add(agentId);
      else next.delete(agentId);
      return next;
    });
  }

  private buildMockSetup(agent: AgentProfile): ApiAgentSetup {
    const env = this.mockEnv.get(agent.id) ?? {};
    return {
      envPath: `/opt/data/profiles/${agent.name}/.env`,
      envExists: this.mockEnv.has(agent.id),
      apiKeys: MOCK_SETUP_API_KEYS.map(([label, envVar]) => ({
        label, envVar, set: !!env[envVar], masked: maskTail(env[envVar]) || null,
      })),
      authProviders: [
        { label: 'Nous Portal', ok: false, status: 'not logged in (run: hermes portal)', hint: 'hermes portal' },
        { label: 'OpenAI Codex', ok: false, status: 'not logged in (run: hermes codex)', hint: 'hermes codex' },
      ],
      apiKeyProviders: [],
      messaging: MOCK_SETUP_MESSAGING.map(([label, tokenVar, homeVar]) => ({
        label, tokenVar, homeVar,
        ok: !!env[tokenVar],
        status: env[tokenVar] ? 'configured' : 'not configured',
        homeChannel: homeVar ? env[homeVar] ?? null : null,
      })),
    };
  }
}
