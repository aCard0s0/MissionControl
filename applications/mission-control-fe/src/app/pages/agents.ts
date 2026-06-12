import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';

const NOUS_MODELS = ['hermes-4-405b', 'hermes-4-70b'];
const OLLAMA_PREFIX = 'ollama: ';

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
  protected readonly providers = computed(() => [
    'anthropic', 'openai', 'nous (local)',
    ...this.store.modelProviders().map(p => OLLAMA_PREFIX + p.name),
  ]);

  protected readonly createOpen = signal(false);
  protected name = '';
  protected provider = 'anthropic';
  protected model = '';
  protected apiKey = '';
  protected cloneFrom = '';

  protected readonly models = signal<string[]>([]);
  protected readonly modelsLoading = signal(false);
  private loadSeq = 0;

  protected readonly totals = computed(() => {
    const as = this.store.containerAgents();
    return {
      msgs: as.reduce((s, a) => s + a.msgsToday, 0),
      tokens: as.reduce((s, a) => s + a.tokensToday, 0),
      active: as.filter(a => a.state === 'active').length,
    };
  });

  protected openCreate(): void {
    this.createOpen.set(true);
    void this.loadModels(this.provider);
  }

  protected onProvider(p: string): void {
    void this.loadModels(p);
  }

  protected upIntegrations(agentId: string): string[] {
    const a = this.store.agentById(agentId);
    return a ? a.integrations.filter(i => i.status === 'up' || i.status === 'degraded').map(i => i.kind) : [];
  }

  protected apiKeyRequired(): boolean {
    return this.provider === 'anthropic' || this.provider === 'openai';
  }

  /** Live catalog refresh from the provider API — anthropic/openai with a key only. */
  protected refreshLive(): void {
    const key = this.apiKey.trim();
    if (!this.apiKeyRequired() || !key) return;
    void this.applyModels(this.store.modelCatalogLive(this.provider, key), this.models());
  }

  private ollamaProvider(label: string) {
    return this.store.modelProviders().find(p => OLLAMA_PREFIX + p.name === label) ?? null;
  }

  private loadModels(p: string): Promise<void> {
    if (p === 'nous (local)') return this.applyModels(Promise.resolve(NOUS_MODELS));
    if (p.startsWith(OLLAMA_PREFIX)) {
      const op = this.ollamaProvider(p);
      return this.applyModels(op
        ? this.store.providerModels(op.id).then(list => list.map(m => m.name))
        : Promise.resolve([]));
    }
    return this.applyModels(this.store.modelCatalog(p));
  }

  /** Swap the model dropdown to `fetch`'s result, keeping the selection when still listed. */
  private async applyModels(fetch: Promise<string[]>, fallback: string[] = []): Promise<void> {
    const seq = ++this.loadSeq;
    this.modelsLoading.set(true);
    let list: string[];
    try { list = await fetch; } catch { list = fallback; }
    if (seq !== this.loadSeq) return;   // a newer load superseded this one
    this.models.set(list);
    this.modelsLoading.set(false);
    if (!list.includes(this.model)) this.model = list[0] ?? '';
  }

  protected async create(): Promise<void> {
    const name = this.name.trim().toLowerCase().replace(/\s+/g, '-');
    const container = this.store.selectedContainer();
    if (!name || !container || !this.model) return;
    if (this.apiKeyRequired() && !this.apiKey.trim()) return;
    let provider = this.provider;
    let baseUrl: string | undefined;
    if (provider.startsWith(OLLAMA_PREFIX)) {
      const op = this.ollamaProvider(provider);
      if (!op) return;
      provider = 'ollama';
      baseUrl = op.url.replace(/\/+$/, '') + '/v1';
    }
    const id = await this.store.createAgent(
      container.id, name, provider, this.model,
      this.apiKey.trim(),
      this.cloneFrom || undefined,
      baseUrl,
    );
    this.createOpen.set(false);
    this.name = this.apiKey = this.cloneFrom = '';
    if (id) {
      this.router.navigate(['/agents', id]);
    }
  }
}
