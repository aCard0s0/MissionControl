import { WritableSignal, computed, signal } from '@angular/core';
import { Webhook } from '../models';
import { AgentStore } from './agent-store';
import { StoreContext } from './store-context';

/** Inbound webhooks, each owned by one profile. Like jobs, there is no endpoint
 *  behind them yet, so this holds nothing. */
export class WebhookStore {
  readonly webhooks: WritableSignal<Webhook[]>;

  readonly forSelectedContainer = computed(() => {
    const ids = new Set(this.agents.forSelectedContainer().map(a => a.id));
    return this.webhooks().filter(w => ids.has(w.agentId));
  });

  constructor(private readonly ctx: StoreContext, private readonly agents: AgentStore) {
    this.webhooks = signal([]);
  }

  add(agentId: string, name: string, slug: string, events: string[]): void {
    this.ctx.toast('webhooks require the hermes adapter — not available yet');
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
