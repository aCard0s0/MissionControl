import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { WebhookStore } from '../core/store/webhook-store';
import { WebhookRoute } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

/** Hermes' own default webhook listener port, mirrored from `HermesWebhooks.DEFAULT_PORT`. */
const DEFAULT_WEBHOOK_PORT = 8644;

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
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly webhooks = inject(WebhookStore);
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
    void this.webhooks.refresh();
  }

  protected readonly hooks = computed(() => {
    const all = this.webhooks.forSelectedContainer();
    const filter = this.agentFilter();
    return filter === 'all' ? all : all.filter(w => w.agentId === filter);
  });

  /** The profiles whose listener is off, so the page can offer to turn it on. */
  protected readonly listenersOff = computed(() =>
    this.webhooks.containerListeners().filter(l => !l.enabled));

  protected agentName(id: string): string {
    return this.agents.byId(id)?.name ?? '?';
  }

  protected listenerOf(agentId: string) {
    return this.webhooks.listenerOf(agentId);
  }

  /** The port a `-p` would have to map. Hermes' own default stands in when the listener
   *  config records none, which is what hermes itself would bind. */
  protected listenerPort(agentId: string): number {
    return this.listenerOf(agentId)?.port ?? DEFAULT_WEBHOOK_PORT;
  }

  protected async toggleListener(agentId: string, enabled: boolean): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    await this.webhooks.setListenerEnabled(agentId, enabled);
    this.busy.set(false);
  }

  protected startAdd(): void {
    this.adding.set(true);
    this.fAgent = this.agentFilter() !== 'all'
      ? this.agentFilter()
      : this.agents.forSelectedContainer()[0]?.id ?? '';
    this.fName = this.fPrompt = this.fEvents = this.fDescription = this.fDeliver = '';
  }

  protected async add(): Promise<void> {
    const name = this.fName.trim();
    if (!name || !this.fAgent || this.busy()) return;
    this.busy.set(true);
    const added = await this.webhooks.subscribe(this.fAgent, {
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
    await this.webhooks.remove(route.agentId, route.name);
  }

  /** Reads the full HMAC secret, which a listing deliberately does not carry. */
  protected async reveal(route: WebhookRoute): Promise<void> {
    const secret = await this.webhooks.secretOf(route.agentId, route.name);
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
    const output = await this.webhooks.test(route.agentId, route.name);
    if (output !== null) {
      this.tested.update(all => ({ ...all, [route.name]: output.trim() || 'no output' }));
    }
  }
}

/** Comma-separated input, blank entries dropped. */
function splitList(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}
