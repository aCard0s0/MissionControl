import { WritableSignal, computed, signal } from '@angular/core';
import { AgentRef, ApiAgentProfile, ApiAuxiliaryModel } from '../hermes-api';
import { AgentProfile, Integration, LogEntry, ProfileTemplate } from '../models';
import { seedAgents } from '../mock-data';
import { maskTail } from '../../shared/secret';
import { ContainerStore } from './container-store';
import { LogStore } from './log-store';
import { StoreContext, nid } from './store-context';
import { toAgentProfile } from './wire-mappers';

/** One profile plus the address every `/api/agents` call needs. */
interface ResolvedAgent {
  agent: AgentProfile;
  ref: AgentRef;
}

/**
 * Hermes profiles across every container. The state and the identity plumbing
 * live here; the larger per-profile surfaces (skills, MCP servers, .env setup)
 * are separate slices that address profiles through {@link AgentStore.resolve}
 * and write back through {@link AgentStore.mutate}.
 */
export class AgentStore {
  readonly agents: WritableSignal<AgentProfile[]>;

  readonly forSelectedContainer = computed(() =>
    this.agents().filter(a => a.containerId === this.containers.selectedContainerId()));

  private refreshInFlight = false;

  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly logs: LogStore,
  ) {
    this.agents = signal(ctx.mock ? seedAgents() : []);
  }

  byId = (id: string | null): AgentProfile | null => this.agents().find(a => a.id === id) ?? null;

  /** The profile plus its API address, or null when either is unknown — the
   *  guard every live profile call starts with. */
  resolve(agentId: string): ResolvedAgent | null {
    const agent = this.byId(agentId);
    if (!agent) return null;
    const container = this.containers.byId(agent.containerId);
    if (!container) return null;
    return { agent, ref: { hostId: container.hostId, containerId: agent.containerId, name: agent.name } };
  }

  patch(id: string, patch: Partial<AgentProfile>): void {
    this.agents.update(as => as.map(a => a.id === id ? { ...a, ...patch } : a));
  }

  /** Rewrites one profile's own fields; a no-op once it is gone. */
  update(id: string, change: (agent: AgentProfile) => AgentProfile): void {
    this.agents.update(as => as.map(a => a.id === id ? change(a) : a));
  }

  /**
   * Runs a live profile mutation. They all answer with the refreshed profile, so
   * apply it and report success; on failure toast `<label> failed: …` and report
   * that instead. Returns false when the profile or its container is unknown.
   */
  async mutate(
    agentId: string, label: string, call: (ref: AgentRef) => Promise<ApiAgentProfile>,
  ): Promise<boolean> {
    const resolved = this.resolve(agentId);
    if (!resolved) return false;
    try {
      const updated = await call(resolved.ref);
      // guard: the profile may have been removed while the request was in flight
      if (this.byId(agentId)) this.patch(agentId, toAgentProfile(updated));
      return true;
    } catch (e) {
      this.ctx.toastFailure(label, e);
      return false;
    }
  }

  /** Per-container fan-out, one request per running container. */
  async refresh(): Promise<void> {
    if (this.refreshInFlight) return;   // skip a tick rather than overlap fan-outs
    this.refreshInFlight = true;
    try {
      const containers = this.containers.containers();
      if (!containers.length) {
        this.agents.set([]);
        return;
      }
      const prev = this.agents();
      const lists = await this.ctx.mapPool(containers, 6, c => {
        if (c.status === 'stopped') return Promise.resolve(prev.filter(a => a.containerId === c.id));
        return this.ctx.api.agents.list(c.hostId, c.id)
          .then(list => list.map(a => keepPendingProbes(toAgentProfile(a), prev)))
          // transient per-container failure — keep its last known profiles
          .catch(() => prev.filter(a => a.containerId === c.id));
      });
      this.agents.set(lists.flat());
    } finally {
      this.refreshInFlight = false;
    }
  }

  async create(
    containerId: string,
    name: string,
    provider: string,
    model: string,
    apiKey: string,
    cloneFromId?: string,
    baseUrl?: string,
    templateId?: string,
    auxiliary?: ApiAuxiliaryModel,
  ): Promise<string> {
    if (!this.ctx.mock) {
      const container = this.containers.byId(containerId);
      if (!container) return '';
      try {
        const created = await this.ctx.api.agents.create({
          hostId: container.hostId,
          containerId,
          name,
          provider,
          model,
          apiKey,
          cloneFrom: cloneFromId ? this.byId(cloneFromId)?.name : undefined,
          baseUrl,
          fromTemplateId: templateId || undefined,
          auxiliary,
        });
        return this.adopt(created);
      } catch (e) {
        this.ctx.toastFailure('create profile', e);
        return '';
      }
    }
    return this.createMock(containerId, name, provider, model, apiKey, cloneFromId, templateId);
  }

  /** Folds a profile the backend just created into the list, replacing any row a
   *  concurrent poll already picked up. Returns its id. */
  adopt(api: ApiAgentProfile): string {
    const agent = toAgentProfile(api);
    this.agents.update(as => [...as.filter(a => a.id !== agent.id), agent]);
    return agent.id;
  }

  /** Removes the profile and everything keyed to it. */
  remove(id: string, onRemoved: (agentId: string) => void): void {
    const agent = this.byId(id);
    if (!agent) return;
    if (!this.ctx.mock) {
      const resolved = this.resolve(id);
      if (!resolved) return;
      this.ctx.api.agents.remove(resolved.ref)
        .then(() => {
          this.agents.update(as => as.filter(a => a.id !== id));
          onRemoved(id);
        })
        .catch(e => this.ctx.toastFailure('remove profile', e));
      return;
    }
    this.agents.update(as => as.filter(a => a.id !== id));
    onRemoved(id);
    this.logs.append(agent.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: null,
      msg: `profile "${agent.name}" deleted`,
    });
  }

  async updateSoul(id: string, soul: string): Promise<boolean> {
    const agent = this.byId(id);
    if (!agent) return false;
    if (!this.ctx.mock) {
      const resolved = this.resolve(id);
      if (!resolved) return false;
      try {
        await this.ctx.api.agents.updateSoul(resolved.ref, soul);
        if (this.byId(id)) this.patch(id, { soul });
        return true;
      } catch (e) {
        this.ctx.toastFailure('SOUL.md save', e);
        return false;
      }
    }
    this.patch(id, { soul });
    this.logs.append(agent.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: id,
      msg: 'SOUL.md updated via dashboard',
    });
    return true;
  }

  async updateConfig(agentId: string, configYaml: string): Promise<boolean> {
    const agent = this.byId(agentId);
    if (!agent) return false;
    if (!this.ctx.mock) {
      return this.mutate(agentId, 'config save',
        ref => this.ctx.api.agents.updateConfig(ref, configYaml));
    }
    this.patch(agentId, { configYaml });
    this.logs.append(agent.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId,
      msg: 'config.yaml updated via dashboard',
    });
    return true;
  }

  /** Profile-scoped supervised gateway log. Unlike Docker logs, these entries
   * have an authoritative agent/profile identity. */
  async logTail(agentId: string, tail = 100): Promise<LogEntry[]> {
    const agent = this.byId(agentId);
    if (!agent) return [];
    if (this.ctx.mock) {
      return this.logs.selectedLogs().filter(line => line.agentId === agentId).slice(0, tail);
    }
    const resolved = this.resolve(agentId);
    if (!resolved) return [];
    const lines = await this.ctx.api.agents.logs(resolved.ref, tail);
    return lines
      .map(line => ({ ...line, agentId }))
      .sort((a, b) => b.ts - a.ts);
  }

  /** Simulated connectivity check — resolves each integration after a beat. */
  pingIntegrations(agentId: string): void {
    if (!this.byId(agentId)) return;
    if (!this.ctx.mock) {
      const resolved = this.resolve(agentId);
      if (!resolved) return;
      this.ctx.api.agents.integrations(resolved.ref)
        .then(integrations => this.patch(agentId, {
          integrations: integrations.map(i => ({
            kind: i.kind as Integration['kind'],
            status: i.status as Integration['status'],
            detail: i.detail,
          })),
        }))
        .catch(e => this.ctx.toastFailure('integrations refresh', e));
      return;
    }
    setTimeout(() => {
      this.update(agentId, x => ({
        ...x,
        integrations: x.integrations.map<Integration>(i =>
          i.status === 'off' ? i : { ...i, status: Math.random() < 0.9 ? 'up' : 'degraded' }),
      }));
    }, 900);
  }

  /** Drops every profile of a container, and re-keys the ones that survive an
   *  in-place recreate onto the container id it was minted with. */
  dropContainer(containerId: string): Set<string> {
    const ids = new Set(this.agents().filter(a => a.containerId === containerId).map(a => a.id));
    this.agents.update(as => as.filter(a => a.containerId !== containerId));
    return ids;
  }

  reassignContainer(fromId: string, toId: string): void {
    this.agents.update(as => as.map(a => a.containerId === fromId
      ? { ...a, containerId: toId, state: a.state === 'dormant' ? 'idle' : a.state } : a));
  }

  /** Marks every profile of a stopped container dormant. */
  markDormant(containerId: string): void {
    this.agents.update(as => as.map(a => a.containerId === containerId ? { ...a, state: 'dormant' } : a));
  }

  private createMock(
    containerId: string,
    name: string,
    provider: string,
    model: string,
    apiKey: string,
    cloneFromId?: string,
    templateId?: string,
  ): string {
    const id = nid('a');
    const src = cloneFromId ? this.byId(cloneFromId) : null;
    const agent: AgentProfile = {
      id, containerId, name,
      role: src ? `Clone of ${src.name}` : 'New profile',
      state: 'idle', provider, model,
      apiKeyMasked: maskTail(apiKey) || '…', cwd: `/home/hermes/${name}`,
      soul: src ? src.soul : `# SOUL.md — ${name}\n\nDescribe this agent's personality and directives.\n`,
      memoryMd: '# MEMORY.md\n\n(empty)\n',
      configYaml: `# config.yaml — ${name}\nprovider: ${provider}\nmodel: ${model}\nterminal:\n  cwd: /home/hermes/${name}\n`,
      skills: src ? src.skills.map(s => ({ ...s })) : [
        { id: nid('s'), name: 'daily-briefing', source: 'bundled', version: '2.1.0', description: 'Compile and deliver scheduled briefings', enabled: true },
        { id: nid('s'), name: 'web-research', source: 'bundled', version: '2.1.0', description: 'Multi-source search and synthesis', enabled: true },
      ],
      mcp: [], integrations: [{ kind: 'filesystem', status: 'up', detail: `/home/hermes/${name} (rw)` }],
      sessions: [], msgsToday: 0, tokensToday: 0, errorRate: 0, lastActive: Date.now(),
    };
    const tmpl = templateId ? this.templateSource?.(templateId) : null;
    if (tmpl) {
      if (agent.role === 'New profile') agent.role = `From ${tmpl.name}`;
      if (tmpl.soul) agent.soul = tmpl.soul;
      if (tmpl.memory) agent.memoryMd = tmpl.memory;
      agent.skills = tmpl.skills.map(s => ({
        id: nid('s'), name: s, source: 'bundled' as const, version: '1.0.0', description: '', enabled: true,
      }));
      agent.mcp = tmpl.mcpServers.map(m => ({
        id: nid('m'), name: m.name, transport: m.transport,
        enabled: m.enabled, origin: 'custom' as const, catalogServerId: null,
        syncedRevision: null, catalogRevision: null, updateAvailable: false,
        status: m.enabled ? 'unknown' as const : 'disabled' as const,
        tools: 0, latencyMs: null, url: m.url, command: m.command, args: m.args,
      }));
    }
    this.agents.update(as => [...as, agent]);
    this.logs.append(containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId: id,
      msg: `profile "${name}" created${src ? ` (cloned from ${src.name})` : ''}`,
    });
    return id;
  }

  /** Set by the template slice — mock profile creation can seed from a template,
   *  and this keeps that a one-way lookup instead of a cycle between the two. */
  templateSource?: (templateId: string) => ProfileTemplate | null;
}

/** A profile refresh must not knock an in-flight MCP probe back to `unknown` —
 *  the row would flicker out of its "checking" state mid-test. */
function keepPendingProbes(fresh: AgentProfile, prev: AgentProfile[]): AgentProfile {
  const old = prev.find(p => p.id === fresh.id);
  if (!old) return fresh;
  return {
    ...fresh,
    mcp: fresh.mcp.map(server => {
      const prior = old.mcp.find(m => m.name === server.name);
      return prior?.status === 'checking' && server.status === 'unknown'
        ? { ...server, status: 'checking' as const }
        : server;
    }),
  };
}
