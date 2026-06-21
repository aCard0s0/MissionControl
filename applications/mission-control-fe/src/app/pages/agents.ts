import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { ApiSetupAuthProvider } from '../core/hermes-api';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { OLLAMA_PREFIX, ollamaOptionForBaseUrl } from '../shared/provider-resolve';
import { ago } from '../core/format';

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
  /** Picker options: the LLM provider registry (value=key, shown by label) plus
   *  one entry per registered ollama instance. */
  protected readonly providerOptions = computed(() => [
    ...this.store.llmProviders().map(p => ({ value: p.key, label: p.label })),
    ...this.store.modelProviders().map(p => ({ value: OLLAMA_PREFIX + p.name, label: 'Ollama: ' + p.name })),
  ]);

  protected readonly createOpen = signal(false);
  protected name = '';
  protected provider = 'nous';
  protected model = '';
  protected apiKey = '';
  protected cloneFrom = '';
  protected fromTemplate = '';

  protected readonly models = signal<string[]>([]);
  protected readonly modelsLoading = signal(false);
  private loadSeq = 0;

  // Nous Portal OAuth status for the selected container — Nous needs an
  // out-of-band `hermes portal` login, so warn when it's missing.
  protected readonly authProviders = signal<ApiSetupAuthProvider[]>([]);
  protected readonly nousAuth = computed(() =>
    this.authProviders().find(p => /nous/i.test(p.label)) ?? null);

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
    void this.loadAuthProviders();
  }

  private async loadAuthProviders(): Promise<void> {
    const c = this.store.selectedContainer();
    this.authProviders.set(c ? await this.store.authProviders(c.id) : []);
  }

  protected onProvider(p: string): void {
    this.model = '';   // drop the prior provider's model; catalog load re-selects
    void this.loadModels(p);
  }

  /** Registry entry for the current provider (null for ollama labels). */
  private providerInfo(key: string) {
    return this.store.llmProviders().find(p => p.key === key) ?? null;
  }

  /** Whether Mission Control can list models for this provider; ollama instances
   *  and the catalog-backed cloud providers can, the rest take a free-text id. */
  private hasCatalog(p: string): boolean {
    if (p.startsWith(OLLAMA_PREFIX)) return true;
    return this.providerInfo(p)?.hasCatalog ?? false;
  }

  protected upIntegrations(agentId: string): string[] {
    const a = this.store.agentById(agentId);
    return a ? a.integrations.filter(i => i.status === 'up' || i.status === 'degraded').map(i => i.kind) : [];
  }

  protected apiKeyRequired(): boolean {
    const info = this.providerInfo(this.provider);
    const needs = info?.needsKey ?? false;   // ollama/nous → false
    if (!needs) return false;
    // a template can carry the provider key — but only skip the prompt when it
    // actually holds a usable (recoverable) secret for THIS provider's env var.
    // Captured templates store key names as unset placeholders, so they don't count.
    if (this.fromTemplate) {
      const t = this.store.templateById(this.fromTemplate);
      const carriesKey = !!t && !!info?.envVar
        && t.secrets.some(s => s.key === info.envVar && s.set && s.recoverable);
      return !carriesKey;
    }
    return true;
  }

  /** Prefill provider/model from the chosen template (keys come from the template). */
  protected onTemplate(id: string): void {
    this.fromTemplate = id;
    const t = this.store.templateById(id);
    if (!t) return;
    const option = this.matchProviderOption(t.provider, t.baseUrl);
    if (option) {
      this.provider = option;
      // pass the template model as the preferred selection so the catalog load's
      // seq guard governs it too — a provider switch mid-load can't let this stale
      // model land on the new provider.
      void this.loadModels(option, t.model);
    } else if (t.model) {
      this.model = t.model;
    }
  }

  /** Maps a stored template provider back to a selectable dropdown option.
   *  Templates persist ollama as a bare `ollama` + baseUrl, but the dropdown
   *  lists `ollama: <name>` per registered instance — resolve it by matching the
   *  baseUrl to a known provider (falling back to the first ollama instance) so
   *  the prefilled provider and model never end up mismatched. Returns null when
   *  nothing matches (caller then prefills the model only). */
  private matchProviderOption(provider: string, baseUrl: string): string | null {
    if (this.providerOptions().some(o => o.value === provider)) return provider;
    if (provider === 'ollama') return ollamaOptionForBaseUrl(baseUrl, this.store.modelProviders());
    return null;
  }

  /** Live catalog refresh from the provider API — catalog-backed providers with a key. */
  protected refreshLive(): void {
    const key = this.apiKey.trim();
    if (!this.apiKeyRequired() || !key || !this.hasCatalog(this.provider)) return;
    void this.applyModels(this.store.modelCatalogLive(this.provider, key), this.models());
  }

  private ollamaProvider(label: string) {
    return this.store.modelProviders().find(p => OLLAMA_PREFIX + p.name === label) ?? null;
  }

  private loadModels(p: string, preferred?: string): Promise<void> {
    if (p.startsWith(OLLAMA_PREFIX)) {
      const op = this.ollamaProvider(p);
      return this.applyModels(op
        ? this.store.providerModels(op.id).then(list => list.map(m => m.name))
        : Promise.resolve([]), [], preferred);
    }
    // free-text providers (no catalog) get no suggestions — the user types the id
    if (!this.hasCatalog(p)) return this.applyModels(Promise.resolve([]), [], preferred);
    return this.applyModels(this.store.modelCatalog(p), [], preferred);
  }

  /** Refresh the model suggestion list. The model field is a free-text input
   *  backed by a datalist, so suggestions only auto-fill the selection when the
   *  list is non-empty (a catalog provider) — free-text providers keep what the
   *  user typed. A `preferred` model (e.g. from a template) wins when set, but
   *  still respects the seq guard so a superseded load can't apply it. */
  private async applyModels(fetch: Promise<string[]>, fallback: string[] = [], preferred?: string): Promise<void> {
    const seq = ++this.loadSeq;
    this.modelsLoading.set(true);
    let list: string[];
    try { list = await fetch; } catch { list = fallback; }
    if (seq !== this.loadSeq) return;   // a newer load superseded this one
    this.models.set(list);
    this.modelsLoading.set(false);
    if (preferred) this.model = preferred;
    else if (list.length && !list.includes(this.model)) this.model = list[0];
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
      this.fromTemplate || undefined,
    );
    this.createOpen.set(false);
    this.name = this.apiKey = this.cloneFrom = this.fromTemplate = '';
    if (id) {
      this.router.navigate(['/agents', id]);
    }
  }
}
