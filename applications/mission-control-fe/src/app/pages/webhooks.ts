import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago, clock } from '../core/format';

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
  protected readonly clock = clock;

  protected readonly agentFilter = signal<string>('all');
  protected readonly adding = signal(false);

  protected fName = '';
  protected fSlug = '';
  protected fEvents = '';
  protected fAgent = '';

  protected readonly hooks = computed(() => {
    const all = this.store.containerWebhooks();
    const f = this.agentFilter();
    return f === 'all' ? all : all.filter(w => w.agentId === f);
  });

  protected agentName(id: string): string {
    return this.store.agentById(id)?.name ?? '?';
  }

  protected startAdd(): void {
    this.adding.set(true);
    this.fAgent = this.agentFilter() !== 'all'
      ? this.agentFilter()
      : this.store.containerAgents()[0]?.id ?? '';
    this.fName = this.fSlug = this.fEvents = '';
  }

  protected add(): void {
    if (!this.fName.trim() || !this.fAgent) return;
    const slug = this.fSlug.trim() || '/hooks/' + this.agentName(this.fAgent) + '/' + this.fName.trim().toLowerCase().replace(/\s+/g, '-');
    const events = this.fEvents.split(',').map(e => e.trim()).filter(Boolean);
    this.store.addWebhook(this.fAgent, this.fName.trim(), slug, events.length ? events : ['*']);
    this.adding.set(false);
  }
}
