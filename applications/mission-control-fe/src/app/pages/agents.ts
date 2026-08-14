import { ChangeDetectionStrategy, Component, WritableSignal, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile } from '../core/models';
import { ApiAuxiliaryModel, ApiSetupAuthProvider } from '../core/hermes-api';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { OLLAMA_PREFIX, ollamaOptionForBaseUrl } from '../shared/provider-resolve';
import { ago } from '../core/format';

/**
 * The CLI invocation that drops you into a session with `name`. Hermes takes
 * `-p` only for named profiles — `default` lives at /opt/data and is invoked
 * bare (the same special-case the backend applies in HermesProfiles).
 * Returns undefined for a name that could carry shell metacharacters, which
 * downgrades the shortcut to a plain shell rather than typing it blind.
 */
export function agentSessionCommand(name: string): string | undefined {
  if (!/^[A-Za-z0-9._-]+$/.test(name)) return undefined;
  return name === 'default' ? 'hermes' : `hermes -p ${name}`;
}

/**
 * One model picker's state: the suggestion list, its loading flag, a monotonic
 * guard so a superseded catalog load cannot land on a provider the user has
 * since switched away from, and the field the selection writes to. The create
 * dialog runs two of these — the agent's main model and the optional auxiliary
 * override — and they must not share a guard, or loading one would cancel the
 * other.
 */
interface ModelSlot {
  models: WritableSignal<string[]>;
  loading: WritableSignal<boolean>;
  seq: number;
  read: () => string;
  write: (model: string) => void;
}

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

  // Auxiliary side tasks (compression, summarization, memory flush, …) run on the
  // main model unless this override is on — worth turning on when the main model
  // is expensive, since side tasks are frequent, short and mechanical.
  protected auxOverride = false;
  protected auxProvider = '';
  protected auxModel = '';
  protected auxApiKey = '';

  protected readonly models = signal<string[]>([]);
  protected readonly modelsLoading = signal(false);
  protected readonly auxModels = signal<string[]>([]);
  protected readonly auxModelsLoading = signal(false);

  private readonly mainSlot: ModelSlot = {
    models: this.models, loading: this.modelsLoading, seq: 0,
    read: () => this.model, write: m => { this.model = m; },
  };
  private readonly auxSlot: ModelSlot = {
    models: this.auxModels, loading: this.auxModelsLoading, seq: 0,
    read: () => this.auxModel, write: m => { this.auxModel = m; },
  };

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
    void this.loadModels(this.mainSlot, this.provider);
    void this.loadAuthProviders();
  }

  private async loadAuthProviders(): Promise<void> {
    const c = this.store.selectedContainer();
    this.authProviders.set(c ? await this.store.authProviders(c.id) : []);
  }

  protected onProvider(p: string): void {
    this.model = '';   // drop the prior provider's model; catalog load re-selects
    void this.loadModels(this.mainSlot, p);
  }

  /** Turning the override on starts it from the main provider, so the common case
   *  — same provider, cheaper model — is one field away. Turning it off clears the
   *  fields rather than leaving them to be silently re-sent on the next open. */
  protected onAuxOverride(on: boolean): void {
    this.auxOverride = on;
    if (!on) {
      this.auxProvider = this.auxModel = this.auxApiKey = '';
      return;
    }
    this.auxProvider = this.auxProvider || this.provider;
    void this.loadModels(this.auxSlot, this.auxProvider);
  }

  protected onAuxProvider(p: string): void {
    this.auxProvider = p;
    this.auxModel = '';
    void this.loadModels(this.auxSlot, p);
  }

  /** The override needs its own key only when it introduces a provider the main
   *  model does not already authenticate. */
  protected auxApiKeyRequired(): boolean {
    if (!this.auxOverride || this.auxProvider === this.provider) return false;
    if (this.auxProvider.startsWith(OLLAMA_PREFIX)) return false;
    return this.providerInfo(this.auxProvider)?.needsKey ?? false;
  }

  /** An override that names no model is not an override — the toggle is on but the
   *  form is incomplete, which the create button refuses. */
  protected auxIncomplete(): boolean {
    if (!this.auxOverride) return false;
    return !this.auxModel.trim() || (this.auxApiKeyRequired() && !this.auxApiKey.trim());
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

  /** The terminal panel is live-mode only, so the shell shortcut is too. */
  protected readonly liveMode = this.store.config.dataMode === 'live';
  protected readonly agentCommand = agentSessionCommand;

  /** Open the terminal panel on a shell already running this agent. */
  protected openShell(a: AgentProfile): void {
    const c = this.store.containers().find(x => x.id === a.containerId);
    if (!c) return;
    this.store.openTerminal({
      hostId: c.hostId,
      containerId: c.id,
      label: a.name,
      agentKey: a.id,
      command: agentSessionCommand(a.name),
    });
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
      void this.loadModels(this.mainSlot, option, t.model);
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
    void this.applyModels(this.mainSlot, this.store.modelCatalogLive(this.provider, key), this.models());
  }

  private ollamaProvider(label: string) {
    return this.store.modelProviders().find(p => OLLAMA_PREFIX + p.name === label) ?? null;
  }

  /** Splits a dropdown option into the (provider, baseUrl) pair the API takes.
   *  Ollama is listed one option per registered instance, but hermes stores it as
   *  a bare `ollama` plus that instance's endpoint. Returns null when the option
   *  names an ollama instance that is no longer registered. */
  private resolveProvider(option: string): { provider: string; baseUrl?: string } | null {
    if (!option.startsWith(OLLAMA_PREFIX)) return { provider: option };
    const op = this.ollamaProvider(option);
    return op ? { provider: 'ollama', baseUrl: op.url.replace(/\/+$/, '') + '/v1' } : null;
  }

  private loadModels(slot: ModelSlot, p: string, preferred?: string): Promise<void> {
    if (p.startsWith(OLLAMA_PREFIX)) {
      const op = this.ollamaProvider(p);
      return this.applyModels(slot, op
        ? this.store.providerModels(op.id).then(list => list.map(m => m.name))
        : Promise.resolve([]), [], preferred);
    }
    // free-text providers (no catalog) get no suggestions — the user types the id
    if (!this.hasCatalog(p)) return this.applyModels(slot, Promise.resolve([]), [], preferred);
    return this.applyModels(slot, this.store.modelCatalog(p), [], preferred);
  }

  /** Refresh the model suggestion list. The model field is a free-text input
   *  backed by a datalist, so suggestions only auto-fill the selection when the
   *  list is non-empty (a catalog provider) — free-text providers keep what the
   *  user typed. A `preferred` model (e.g. from a template) wins when set, but
   *  still respects the seq guard so a superseded load can't apply it. */
  private async applyModels(
    slot: ModelSlot, fetch: Promise<string[]>, fallback: string[] = [], preferred?: string,
  ): Promise<void> {
    const seq = ++slot.seq;
    slot.loading.set(true);
    let list: string[];
    try { list = await fetch; } catch { list = fallback; }
    if (seq !== slot.seq) return;   // a newer load superseded this one
    slot.models.set(list);
    slot.loading.set(false);
    if (preferred) slot.write(preferred);
    else if (list.length && !list.includes(slot.read())) slot.write(list[0]);
  }

  protected async create(): Promise<void> {
    const name = this.name.trim().toLowerCase().replace(/\s+/g, '-');
    const container = this.store.selectedContainer();
    if (!name || !container || !this.model) return;
    if (this.apiKeyRequired() && !this.apiKey.trim()) return;
    if (this.auxIncomplete()) return;
    const main = this.resolveProvider(this.provider);
    if (!main) return;
    const auxiliary = this.auxiliaryOverride();
    if (this.auxOverride && !auxiliary) return;   // named an ollama instance that vanished
    const id = await this.store.createAgent(
      container.id, name, main.provider, this.model,
      this.apiKey.trim(),
      this.cloneFrom || undefined,
      main.baseUrl,
      this.fromTemplate || undefined,
      auxiliary,
    );
    this.createOpen.set(false);
    this.name = this.apiKey = this.cloneFrom = this.fromTemplate = '';
    this.onAuxOverride(false);
    if (id) {
      this.router.navigate(['/agents', id]);
    }
  }

  /** The auxiliary payload, or undefined when side tasks should follow the main
   *  model. An override on the main provider sends no provider or endpoint of its
   *  own — the backend then inherits the main ones, so switching only the model
   *  cannot drift the two apart. */
  private auxiliaryOverride(): ApiAuxiliaryModel | undefined {
    if (!this.auxOverride || !this.auxModel.trim()) return undefined;
    if (this.auxProvider === this.provider) return { model: this.auxModel.trim() };
    const aux = this.resolveProvider(this.auxProvider);
    if (!aux) return undefined;
    return {
      provider: aux.provider,
      model: this.auxModel.trim(),
      baseUrl: aux.baseUrl,
      apiKey: this.auxApiKey.trim() || undefined,
    };
  }
}
