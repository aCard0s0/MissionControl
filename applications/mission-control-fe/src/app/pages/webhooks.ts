import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { WebhookStore } from '../core/store/webhook-store';
import { OutboundWebhook, WebhookRoute } from '../core/models';
import {
  HERMES_HOOK_EVENTS, isKnownHookEvent, matcherApplies,
} from '../core/hermes-hook-events';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

/** Hermes' own default webhook listener port, mirrored from `HermesWebhooks.DEFAULT_PORT`. */
const DEFAULT_WEBHOOK_PORT = 8644;

/**
 * Webhooks in both directions, one page with a direction toggle.
 *
 * **Inbound** — routes that wake a profile when something posts to them. Two things gate a
 * route firing, and the page says which one is missing: the profile's listener has to be
 * enabled, and nothing outside the docker network can reach it until an operator publishes
 * the port themselves. Mission Control never receives the traffic.
 *
 * **Outbound** — targets the agent POSTs signed lifecycle events to, from `hooks.outbound`
 * in its config. These need no listener and no published port, because the agent is the one
 * making the connection. Hermes reads the list at startup, so a change here lands on the
 * next gateway restart, which the page says rather than leaving an operator to wonder why
 * nothing is arriving.
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

  protected readonly direction = signal<'inbound' | 'outbound'>('inbound');

  /** The outbound target being edited, or 'new'; null when the form is closed. */
  protected readonly editingOutbound = signal<OutboundWebhook | 'new' | null>(null);

  protected readonly eventGroups = HERMES_HOOK_EVENTS;
  protected readonly isKnownHookEvent = isKnownHookEvent;

  /** Secrets an operator has asked to see, by route name. */
  protected readonly revealed = signal<Record<string, string>>({});
  /** The last test result, by route name. */
  protected readonly tested = signal<Record<string, string>>({});

  /** Literal braces, kept out of the template so Angular does not read them as ICU. */
  protected readonly promptExample = 'Alert {alert.name} is {status}';

  /** The outbound form. One object because every field is set and cleared together —
   *  `startOutbound` is one assignment rather than seven. `events` stays a signal: the
   *  chips toggle it, and the matcher hint recomputes off it. */
  protected oForm = emptyOutboundForm();
  protected readonly oEvents = signal<string[]>([]);

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

  protected readonly outboundTargets = computed(() => {
    const filter = this.agentFilter();
    const all = this.webhooks.outbound()
      .filter(o => this.agents.byId(o.agentId));   // only the selected container's profiles
    return filter === 'all' ? all : all.filter(o => o.agentId === filter);
  });

  /** A matcher only does anything for the two tool-scoped events; hermes ignores it
   *  elsewhere, so the form says so instead of silently writing a key with no effect. */
  protected readonly matcherApplies = computed(() => matcherApplies(this.oEvents()));

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

  // ── outbound targets ─────────────────────────────────────────────────────

  protected toggleEvent(event: string): void {
    this.oEvents.update(events => events.includes(event)
      ? events.filter(e => e !== event)
      : [...events, event]);
  }

  protected startOutbound(target?: OutboundWebhook): void {
    this.editingOutbound.set(target ?? 'new');
    this.oForm = {
      agent: target?.agentId ?? (this.agentFilter() !== 'all'
        ? this.agentFilter()
        : this.agents.forSelectedContainer()[0]?.id ?? ''),
      name: target?.name ?? '',
      url: target?.url ?? '',
      matcher: target?.matcher ?? '',
      timeout: target?.timeout === null ? '' : String(target?.timeout ?? ''),
      secretEnv: target?.secretEnv ?? '',
    };
    this.oEvents.set([...(target?.events ?? [])]);
  }

  protected async saveOutbound(): Promise<void> {
    const editing = this.editingOutbound();
    const form = this.oForm;
    const url = form.url.trim();
    if (!editing || !form.agent || !url || !this.oEvents().length || this.busy()) return;
    const timeout = form.timeout.trim() ? Number(form.timeout) : null;
    const request = {
      name: form.name.trim(),
      url,
      events: this.oEvents(),
      matcher: form.matcher.trim() || null,
      timeout: Number.isFinite(timeout) ? timeout : null,
      secretEnv: form.secretEnv.trim().toUpperCase() || null,
    };
    this.busy.set(true);
    const saved = editing === 'new'
      ? await this.webhooks.addOutbound(form.agent, request)
      : await this.webhooks.updateOutbound(editing.agentId, editing.index, request);
    this.busy.set(false);
    if (saved) this.editingOutbound.set(null);
  }

  protected async removeOutbound(target: OutboundWebhook): Promise<void> {
    const label = target.name || target.url;
    if (!confirm(`Remove outbound webhook "${label}"? The agent stops posting to it.`)) return;
    await this.webhooks.removeOutbound(target.agentId, target.index);
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

function emptyOutboundForm() {
  return { agent: '', name: '', url: '', matcher: '', timeout: '', secretEnv: '' };
}

/** Comma-separated input, blank entries dropped. */
function splitList(value: string): string[] {
  return value.split(',').map(item => item.trim()).filter(Boolean);
}
