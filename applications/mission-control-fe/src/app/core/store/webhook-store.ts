import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { ApiOutboundWebhookRequest, ApiSubscribeWebhookRequest, ApiWebhooks } from '../hermes-api';
import { OutboundWebhook, WebhookListener, WebhookRoute } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/**
 * Webhooks in both directions, which hermes owns per profile.
 *
 * Two things have to be true before a route fires: the profile's listener is enabled, and a
 * route exists under it. Mission Control manages both and never carries webhook traffic —
 * an agent container publishes no port, so {@link WebhookListener.published} stays false and
 * the page says a route is configured but not yet reachable.
 *
 * Secrets are hermes'. A listing carries only a masked tail; {@link secretOf} asks for the
 * full value, once, when an operator opens it.
 *
 * Outbound targets are the other direction — where the agent pushes signed lifecycle events.
 * They live in `config.yaml` rather than hermes' webhook store, and hermes reads them at
 * startup, so an edit lands on the next gateway restart.
 */
@Injectable({ providedIn: 'root' })
export class WebhookStore {
  readonly routes: WritableSignal<WebhookRoute[]> = signal([]);
  readonly listeners: WritableSignal<WebhookListener[]> = signal([]);
  readonly outbound: WritableSignal<OutboundWebhook[]> = signal([]);

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

  private readonly ctx = inject(StoreContext);
  private readonly agents = inject(AgentStore);

  constructor() {
    // the routes on screen belong to the selected container's profiles, so a
    // switch re-reads rather than waiting out the poll
    inject(ContainerStore).onSelect(() => void this.refresh());
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
        this.outbound.set([]);
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
      this.outbound.set(found.flatMap(({ profile, answer }) =>
        toOutbound(answer, profile.id, profile.containerId)));
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

  /**
   * Outbound targets. Hermes reads `hooks.outbound` at startup, so these land on the next
   * gateway restart rather than immediately — the page says so rather than implying an edit
   * took effect the moment it saved.
   */
  addOutbound(agentId: string, request: ApiOutboundWebhookRequest): Promise<boolean> {
    return this.mutate(agentId, 'add outbound webhook',
      ref => this.ctx.api.agents.webhooks.addOutbound(ref, request));
  }

  updateOutbound(
    agentId: string, index: number, request: ApiOutboundWebhookRequest,
  ): Promise<boolean> {
    return this.mutate(agentId, 'save outbound webhook',
      ref => this.ctx.api.agents.webhooks.updateOutbound(ref, index, request));
  }

  removeOutbound(agentId: string, index: number): Promise<boolean> {
    return this.mutate(agentId, 'remove outbound webhook',
      ref => this.ctx.api.agents.webhooks.removeOutbound(ref, index));
  }

  /** The full HMAC secret, or null when it could not be read. */
  async secretOf(agentId: string, route: string): Promise<string | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return null;
    }
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
    if (!resolved) {
      this.ctx.gone('profile');
      return null;
    }
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
    if (!resolved) return this.ctx.gone('profile');
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
      this.outbound.update(os => [
        ...os.filter(o => o.agentId !== agentId),
        ...toOutbound(answer, agentId, containerId),
      ]);
      return true;
    } catch (e) {
      this.ctx.toastFailure(label, e);
      return false;
    }
  }
}

/** Position is the identity, so it is taken from the array rather than from the payload. */
function toOutbound(
  answer: ApiWebhooks, agentId: string, containerId: string,
): OutboundWebhook[] {
  return (answer.outbound ?? []).map((target, index) => ({
    index,
    name: target.name ?? '',
    url: target.url,
    events: target.events ?? [],
    matcher: target.matcher ?? null,
    timeout: target.timeout ?? null,
    secretEnv: target.secretEnv ?? null,
    literalSecret: !!target.literalSecret,
    agentId,
    containerId,
  }));
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
