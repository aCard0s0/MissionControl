import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { WebhookRoute } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

/**
 * Inbound webhooks — routes that wake a profile when something posts to them.
 *
 * Two things gate a route firing, and the page says which one is missing: the profile's
 * listener has to be enabled, and nothing outside the docker network can reach it until an
 * operator publishes the port themselves. Mission Control never receives the traffic.
 */
@Component({
  selector: 'mc-webhooks',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './webhooks.html',
  styleUrl: './webhooks.scss',
})
export class WebhooksPage {
  protected readonly store = inject(HermesStore);
  protected readonly ago = ago;

  protected readonly agentFilter = signal<string>('all');
  protected readonly adding = signal(false);
  protected readonly busy = signal(false);

  /** Secrets an operator has asked to see, by route name. */
  protected readonly revealed = signal<Record<string, string>>({});
  /** The last test result, by route name. */
  protected readonly tested = signal<Record<string, string>>({});

  /** Literal braces, kept out of the template so Angular does not read them as ICU. */
  protected readonly promptExample = 'Alert {alert.name} is {status}';

  protected fName = '';
  protected fPrompt = '';
  protected fEvents = '';
  protected fDescription = '';
  protected fDeliver = '';
  protected fAgent = '';

  constructor() {
    void this.store.refreshWebhooks();
  }

  protected readonly hooks = computed(() => {
    const all = this.store.containerWebhooks();
    const filter = this.agentFilter();
    return filter === 'all' ? all : all.filter(w => w.agentId === filter);
  });

  /** The profiles whose listener is off, so the page can offer to turn it on. */
  protected readonly listenersOff = computed(() =>
    this.store.webhookListeners().filter(l => !l.enabled));

  protected agentName(id: string): string {
    return this.store.agentById(id)?.name ?? '?';
  }

  protected listenerOf(agentId: string) {
    return this.store.webhookListenerOf(agentId);
  }

  protected async toggleListener(agentId: string, enabled: boolean): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    await this.store.setWebhookListener(agentId, enabled);
    this.busy.set(false);
  }

  protected startAdd(): void {
    this.adding.set(true);
    this.fAgent = this.agentFilter() !== 'all'
      ? this.agentFilter()
      : this.store.containerAgents()[0]?.id ?? '';
    this.fName = this.fPrompt = this.fEvents = this.fDescription = this.fDeliver = '';
  }

  protected async add(): Promise<void> {
    const name = this.fName.trim();
    if (!name || !this.fAgent || this.busy()) return;
    this.busy.set(true);
    const added = await this.store.addWebhook(this.fAgent, {
      name,
      prompt: this.fPrompt.trim() || undefined,
      description: this.fDescription.trim() || undefined,
      events: splitList(this.fEvents),
      deliver: this.fDeliver.trim() || undefined,
    });
    this.busy.set(false);
    if (added) this.adding.set(false);
  }

  protected async remove(route: WebhookRoute): Promise<void> {
    if (!confirm(`Remove webhook "${route.name}"? Anything posting to it will stop working.`)) {
      return;
    }
    await this.store.removeWebhook(route.agentId, route.name);
  }

  /** Reads the full HMAC secret, which a listing deliberately does not carry. */
  protected async reveal(route: WebhookRoute): Promise<void> {
    const secret = await this.store.webhookSecret(route.agentId, route.name);
    if (secret) this.revealed.update(all => ({ ...all, [route.name]: secret }));
  }

  protected hide(route: WebhookRoute): void {
    this.revealed.update(all => {
      const next = { ...all };
      delete next[route.name];
      return next;
    });
  }

  protected async test(route: WebhookRoute): Promise<void> {
    const output = await this.store.testWebhook(route.agentId, route.name);
    if (output !== null) {
      this.tested.update(all => ({ ...all, [route.name]: output.trim() || 'no output' }));
    }
  }
}

/** Comma-separated input, blank entries dropped. */
function splitList(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}
