import { WritableSignal, computed, signal } from '@angular/core';
import { Webhook } from '../models';
import { seedWebhooks } from '../mock-data';
import { AgentStore } from './agent-store';
import { StoreContext, nid } from './store-context';

/** Inbound webhooks, each owned by one profile. Mock-only, like jobs. */
export class WebhookStore {
  readonly webhooks: WritableSignal<Webhook[]>;

  readonly forSelectedContainer = computed(() => {
    const ids = new Set(this.agents.forSelectedContainer().map(a => a.id));
    return this.webhooks().filter(w => ids.has(w.agentId));
  });

  constructor(private readonly ctx: StoreContext, private readonly agents: AgentStore) {
    this.webhooks = signal(ctx.mock ? seedWebhooks() : []);
  }

  add(agentId: string, name: string, slug: string, events: string[]): void {
    if (!this.ctx.mock) {
      this.ctx.toast('webhooks require the hermes adapter — not available in live mode yet');
      return;
    }
    this.webhooks.update(ws => [...ws, {
      id: nid('w'), agentId, name, slug,
      secretMasked: 'whsec_…' + Math.random().toString(16).slice(2, 6),
      events, active: true, deliveries: [],
    }]);
  }

  toggle(id: string): void {
    this.webhooks.update(ws => ws.map(w => w.id === id ? { ...w, active: !w.active } : w));
  }

  remove(id: string): void {
    this.webhooks.update(ws => ws.filter(w => w.id !== id));
  }

  dropByAgent(agentId: string): void {
    this.webhooks.update(ws => ws.filter(w => w.agentId !== agentId));
  }

  dropByAgents(agentIds: Set<string>): void {
    this.webhooks.update(ws => ws.filter(w => !agentIds.has(w.agentId)));
  }
}
