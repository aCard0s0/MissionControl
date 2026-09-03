import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { AgentRef, ApiAgentProfile } from '../hermes-api';
import { AgentProfile, Integration, LogEntry, NewAgent } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { toAgentProfile, toLogEntry } from './wire-mappers';

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
@Injectable({ providedIn: 'root' })
export class AgentStore {
  readonly agents: WritableSignal<AgentProfile[]>;

  readonly forSelectedContainer = computed(() =>
    this.agents().filter(a => a.containerId === this.containers.selectedContainerId()));

  private refreshInFlight = false;

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);

  constructor() {
    this.agents = signal([]);
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
    if (!resolved) return this.ctx.gone('profile');
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

  /**
   * Creates a profile and folds it in, answering its id — or '' when the create failed, which
   * it has already reported.
   *
   * <p>`cloneFrom` is a profile *id* here and a profile *name* on the wire: an id is what a
   * picker holds, a name is what the container addresses files by, and resolving one to the
   * other needs the profile list. That translation is the reason this takes a domain record
   * rather than handing {@link CreateAgentRequest} straight through.
   */
  async create(request: NewAgent): Promise<string> {
    const container = this.containers.byId(request.containerId);
    if (!container) {
      this.ctx.gone('container');
      return '';
    }
    try {
      const created = await this.ctx.api.agents.create({
        hostId: container.hostId,
        containerId: request.containerId,
        name: request.name,
        provider: request.provider,
        model: request.model,
        apiKey: request.apiKey,
        apiKeyCredentialId: request.apiKeyCredentialId || undefined,
        cloneFrom: request.cloneFrom ? this.byId(request.cloneFrom)?.name : undefined,
        baseUrl: request.baseUrl,
        fromTemplateId: request.fromTemplate || undefined,
        auxiliary: request.auxiliary,
      });
      this.ctx.notify(`profile ${request.name} created`);
      return this.adopt(created);
    } catch (e) {
      this.ctx.toastFailure('create profile', e);
      return '';
    }
  }

  /** Folds a profile the backend just created into the list, replacing any row a
   *  concurrent poll already picked up. Returns its id. */
  adopt(api: ApiAgentProfile): string {
    const agent = toAgentProfile(api);
    this.agents.update(as => [...as.filter(a => a.id !== agent.id), agent]);
    return agent.id;
  }

  /** Removes the profile itself. What else was keyed to it is {@link AgentRemoval}'s
   *  to forget — this slice does not know the stores that hold it. */
  async remove(id: string): Promise<boolean> {
    const resolved = this.resolve(id);
    if (!resolved) return this.ctx.gone('profile');
    try {
      await this.ctx.api.agents.remove(resolved.ref);
      this.agents.update(as => as.filter(a => a.id !== id));
      return true;
    } catch (e) {
      this.ctx.toastFailure('remove profile', e);
      return false;
    }
  }

  async updateSoul(id: string, soul: string): Promise<boolean> {
    const resolved = this.resolve(id);
    if (!resolved) return this.ctx.gone('profile');
    try {
      await this.ctx.api.agents.updateSoul(resolved.ref, soul);
      // guard: the profile may have been removed while the request was in flight
      if (this.byId(id)) this.patch(id, { soul });
      return true;
    } catch (e) {
      this.ctx.toastFailure('SOUL.md save', e);
      return false;
    }
  }

  updateConfig(agentId: string, configYaml: string): Promise<boolean> {
    return this.mutate(agentId, 'config save',
      ref => this.ctx.api.agents.updateConfig(ref, configYaml));
  }

  /**
   * Hermes' own emergency stop for this profile — not a container stop. Cron dispatch,
   * kanban dispatch and new gateway turns are held; whatever is mid-turn finishes.
   */
  pause(agentId: string, reason?: string): Promise<boolean> {
    return this.mutate(agentId, 'pause', ref => this.ctx.api.agents.pause(ref, reason));
  }

  resume(agentId: string): Promise<boolean> {
    return this.mutate(agentId, 'resume', ref => this.ctx.api.agents.resume(ref));
  }

  /** Profile-scoped supervised gateway log. Unlike Docker logs, these entries
   * have an authoritative agent/profile identity. */
  async logTail(agentId: string, tail = 100): Promise<LogEntry[]> {
    const resolved = this.resolve(agentId);
    if (!resolved) return [];
    const lines = await this.ctx.api.agents.logs(resolved.ref, tail);
    return lines
      .map(line => toLogEntry(line, agentId))
      .sort((a, b) => b.ts - a.ts);
  }

  /** Re-reads every integration's connectivity from the container. */
  pingIntegrations(agentId: string): void {
    const resolved = this.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return;
    }
    this.ctx.api.agents.integrations(resolved.ref)
      .then(integrations => this.patch(agentId, {
        integrations: integrations.map(i => ({
          kind: i.kind,
          status: i.status as Integration['status'],
          detail: i.detail,
        })),
      }))
      .catch(e => this.ctx.toastFailure('integrations refresh', e));
  }

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
