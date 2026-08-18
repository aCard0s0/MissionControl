import { WritableSignal, computed, signal } from '@angular/core';
import { ApiSubscribeWebhookRequest, ApiWebhooks } from '../hermes-api';
import { WebhookListener, WebhookRoute } from '../models';
import { AgentStore } from './agent-store';
import { StoreContext } from './store-context';

/**
 * Inbound webhook routes, which hermes owns per profile.
 *
 * Two things have to be true before a route fires: the profile's listener is enabled, and a
 * route exists under it. Mission Control manages both and never carries webhook traffic —
 * an agent container publishes no port, so {@link WebhookListener.published} stays false and
 * the page says a route is configured but not yet reachable.
 *
 * Secrets are hermes'. A listing carries only a masked tail; {@link secretOf} asks for the
 * full value, once, when an operator opens it.
 */
export class WebhookStore {
  readonly routes: WritableSignal<WebhookRoute[]> = signal([]);
  readonly listeners: WritableSignal<WebhookListener[]> = signal([]);

  readonly forSelectedContainer = computed(() => {
    const ids = new Set(this.agents.forSelectedContainer().map(a => a.id));
    return this.routes().filter(r => ids.has(r.agentId));
  });

  /** The listeners of the selected container's profiles. */
  readonly containerListeners = computed(() => {
    const ids = new Set(this.agents.forSelectedContainer().map(a => a.id));
    return this.listeners().filter(l => ids.has(l.agentId));
  });

  private refreshInFlight = false;

  constructor(
    private readonly ctx: StoreContext,
    private readonly agents: AgentStore,
    onContainerSelect: (listener: () => void) => void,
  ) {
    onContainerSelect(() => void this.refresh());
  }

  /** One read per profile in the selected container, unioned. */
  async refresh(): Promise<void> {
    if (this.refreshInFlight) return;   // skip a tick rather than overlap fan-outs
    this.refreshInFlight = true;
    try {
      const profiles = this.agents.forSelectedContainer();
      if (!profiles.length) {
        this.routes.set([]);
        this.listeners.set([]);
        return;
      }
      const answers = await this.ctx.mapPool(profiles, 6, async profile => {
        const resolved = this.agents.resolve(profile.id);
        if (!resolved) return null;
        try {
          const answer = await this.ctx.api.agents.webhooks.list(resolved.ref);
          return { profile, answer };
        } catch {
          return null;   // a stopped profile keeps the rest of the list
        }
      });
      const found = answers.filter(a => a !== null);
      this.routes.set(found.flatMap(({ profile, answer }) =>
        answer.subscriptions.map(s => toRoute(s, profile.id, profile.containerId))));
      this.listeners.set(found.map(({ profile, answer }) =>
        ({ agentId: profile.id, ...answer.platform })));
    } finally {
      this.refreshInFlight = false;
    }
  }

  listenerOf(agentId: string): WebhookListener | null {
    return this.listeners().find(l => l.agentId === agentId) ?? null;
  }

  setListenerEnabled(agentId: string, enabled: boolean, port?: number): Promise<boolean> {
    return this.mutate(agentId, enabled ? 'enable webhooks' : 'disable webhooks',
      ref => this.ctx.api.agents.webhooks.setPlatform(ref, enabled, undefined, port));
  }

  subscribe(agentId: string, request: ApiSubscribeWebhookRequest): Promise<boolean> {
    return this.mutate(agentId, 'add webhook',
      ref => this.ctx.api.agents.webhooks.subscribe(ref, request));
  }

  remove(agentId: string, route: string): Promise<boolean> {
    return this.mutate(agentId, 'remove webhook',
      ref => this.ctx.api.agents.webhooks.remove(ref, route));
  }

  /** The full HMAC secret, or null when it could not be read. */
  async secretOf(agentId: string, route: string): Promise<string | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) return null;
    try {
      return (await this.ctx.api.agents.webhooks.secret(resolved.ref, route)).secret;
    } catch (e) {
      this.ctx.toastFailure('read webhook secret', e);
      return null;
    }
  }

  /** Fires hermes' test POST at the route and answers with what it printed. */
  async test(agentId: string, route: string): Promise<string | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) return null;
    try {
      return (await this.ctx.api.agents.webhooks.test(resolved.ref, route)).output;
    } catch (e) {
      this.ctx.toastFailure('webhook test', e);
      return null;
    }
  }

  dropByAgent(agentId: string): void {
    this.routes.update(rs => rs.filter(r => r.agentId !== agentId));
    this.listeners.update(ls => ls.filter(l => l.agentId !== agentId));
  }

  dropByAgents(agentIds: Set<string>): void {
    this.routes.update(rs => rs.filter(r => !agentIds.has(r.agentId)));
    this.listeners.update(ls => ls.filter(l => !agentIds.has(l.agentId)));
  }

  /** Folds the answer back in, replacing only the profile it speaks for. */
  private async mutate(
    agentId: string, label: string,
    call: (ref: { hostId: string; containerId: string; name: string }) => Promise<ApiWebhooks>,
  ): Promise<boolean> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) return false;
    try {
      const answer = await call(resolved.ref);
      const containerId = resolved.agent.containerId;
      this.routes.update(rs => [
        ...rs.filter(r => r.agentId !== agentId),
        ...answer.subscriptions.map(s => toRoute(s, agentId, containerId)),
      ]);
      this.listeners.update(ls => [
        ...ls.filter(l => l.agentId !== agentId),
        { agentId, ...answer.platform },
      ]);
      return true;
    } catch (e) {
      this.ctx.toastFailure(label, e);
      return false;
    }
  }
}

function toRoute(
  subscription: ApiWebhooks['subscriptions'][number], agentId: string, containerId: string,
): WebhookRoute {
  return {
    name: subscription.name,
    description: subscription.description ?? '',
    url: subscription.url,
    events: subscription.events,
    prompt: subscription.prompt ?? '',
    skills: subscription.skills,
    deliver: subscription.deliver ?? 'log',
    deliverOnly: subscription.deliverOnly,
    secretMasked: subscription.secretMasked,
    createdAt: subscription.createdAt,
    agentId,
    containerId,
  };
}
