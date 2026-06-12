import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

const MODELS: Record<string, string[]> = {
  anthropic: ['claude-fable-5', 'claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5'],
  openai: ['gpt-5.2', 'gpt-5.2-mini'],
  'nous (local)': ['hermes-4-405b', 'hermes-4-70b'],
};

@Component({
  selector: 'mc-agents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, RollingNumber, Reveal],
  templateUrl: './agents.html',
  styleUrl: './agents.scss',
})
export class AgentsPage {
  protected readonly store = inject(HermesStore);
  private readonly router = inject(Router);

  protected readonly ago = ago;
  protected readonly providers = Object.keys(MODELS);

  protected readonly createOpen = signal(false);
  protected name = '';
  protected provider = 'anthropic';
  protected model = MODELS['anthropic'][0];
  protected apiKey = '';
  protected cloneFrom = '';

  protected readonly modelsFor = computed(() => MODELS[this.providerSig()] ?? []);
  protected readonly providerSig = signal('anthropic');

  protected readonly totals = computed(() => {
    const as = this.store.containerAgents();
    return {
      msgs: as.reduce((s, a) => s + a.msgsToday, 0),
      tokens: as.reduce((s, a) => s + a.tokensToday, 0),
      active: as.filter(a => a.state === 'active').length,
    };
  });

  protected onProvider(p: string): void {
    this.providerSig.set(p);
    this.model = MODELS[p][0];
  }

  protected upIntegrations(agentId: string): string[] {
    const a = this.store.agentById(agentId);
    return a ? a.integrations.filter(i => i.status === 'up' || i.status === 'degraded').map(i => i.kind) : [];
  }

  protected create(): void {
    const name = this.name.trim().toLowerCase().replace(/\s+/g, '-');
    const container = this.store.selectedContainer();
    if (!name || !container || !this.apiKey.trim()) return;
    const masked = this.apiKey.length > 4 ? '…' + this.apiKey.slice(-4) : '…';
    const id = this.store.createAgent(
      container.id, name, this.provider, this.model,
      (this.provider === 'anthropic' ? 'sk-ant-' : 'sk-') + masked,
      this.cloneFrom || undefined,
    );
    this.createOpen.set(false);
    this.name = this.apiKey = this.cloneFrom = '';
    this.router.navigate(['/agents', id]);
  }
}
