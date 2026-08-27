import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentStore } from '../core/store/agent-store';
import { ProviderStore } from '../core/store/provider-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { errorMessage } from '../core/errors';
import { ago, until } from '../core/format';
import { InferenceEndpoint, EndpointModel, PullState, RunningModel } from '../core/models';
import { ENDPOINT_KIND_LABELS } from '../shared/provider-resolve';

/** How often the open panel re-reads what is loaded and what is pulling. */
const POLL_MS = 3000;

/** Beyond this, an expiry is a pin rather than a countdown — see {@link ModelsPage.expiry}. */
const EXPIRY_HORIZON_MS = 86_400_000;

@Component({
  selector: 'mc-models',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './models.html',
  styleUrl: './models.scss',
})
export class ModelsPage {
  protected readonly providers = inject(ProviderStore);
  protected readonly agents = inject(AgentStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly ago = ago;

  /** The chip beside an endpoint: its protocol, plus a version when the protocol has one. */
  protected kindChip(endpoint: InferenceEndpoint): string {
    const label = (ENDPOINT_KIND_LABELS[endpoint.kind ?? ''] ?? endpoint.kind ?? '').toLowerCase();
    return endpoint.version ? `${label} ${endpoint.version}` : label;
  }

  protected readonly addingProvider = signal(false);
  protected provName = '';
  protected provUrl = '';
  /** Endpoint whose remove is armed. Asked twice, like a model: un-registering one silently
   *  breaks the `base_url` of every agent pointed at it. */
  protected readonly removingProvider = signal<string | null>(null);

  protected readonly selectedId = signal<string | null>(null);
  protected readonly models = signal<EndpointModel[]>([]);
  protected readonly modelsLoading = signal(false);
  protected readonly modelsError = signal<string | null>(null);
  /** What the endpoint holds in memory, re-read on every poll. */
  protected readonly running = signal<RunningModel[]>([]);
  /** Model whose start or stop is in flight — a load reads the weights off disk first. */
  protected readonly busyModel = signal<string | null>(null);

  protected pullName = '';
  protected readonly pulls = signal<PullState[]>([]);
  protected readonly removingModel = signal<string | null>(null);

  private pollTimer: ReturnType<typeof setInterval> | null = null;

  private static readonly HTTP_URL = /^https?:\/\/.+/;

  constructor() {
    void this.providers.refresh();
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  protected urlValid(): boolean {
    return ModelsPage.HTTP_URL.test(this.provUrl.trim());
  }

  protected addProvider(): void {
    const name = this.provName.trim();
    const url = this.provUrl.trim();
    if (!name || !ModelsPage.HTTP_URL.test(url)) return;
    this.providers.add(name, url);
    this.addingProvider.set(false);
    this.provName = '';
    this.provUrl = '';
  }

  protected removeProvider(id: string): void {
    this.removingProvider.set(null);
    if (this.selectedId() === id) {
      this.stopPolling();
      this.selectedId.set(null);
      this.models.set([]);
      this.pulls.set([]);
      this.running.set([]);
    }
    this.providers.remove(id);
  }

  protected selected(): InferenceEndpoint | null {
    const id = this.selectedId();
    return this.providers.endpoints().find(p => p.id === id) ?? null;
  }

  protected select(id: string): void {
    if (this.selectedId() === id) return;
    this.stopPolling();
    this.selectedId.set(id);
    this.models.set([]);
    this.pulls.set([]);
    this.running.set([]);
    this.removingModel.set(null);
    this.busyModel.set(null);
    this.pullName = '';
    this.refresh(id);
  }

  /** Re-reads the whole panel: the model list, what is resident, and any pull. Also picks up
   *  a poller for an endpoint that has come back since it was selected. */
  protected refresh(id: string): void {
    void this.loadModels(id);
    // only ollama answers either of these, and only ollama can be pulling anything
    if (this.selected()?.canManageModels) {
      void this.poll(id);
      this.startPolling(id);
    }
  }

  protected async loadModels(id: string): Promise<void> {
    this.modelsLoading.set(true);
    this.modelsError.set(null);
    try {
      const models = await this.providers.models(id);
      if (id !== this.selectedId()) return;   // provider changed mid-flight — stale response
      this.models.set(models);
    } catch (error) {
      if (id !== this.selectedId()) return;
      this.modelsError.set(errorMessage(error, 'failed to load models'));
      this.models.set([]);
    } finally {
      if (id === this.selectedId()) this.modelsLoading.set(false);
    }
  }

  // ── what is in use ──────────────────────────────────────────────────────

  /** The in-memory state of one model, or null when it is only on disk. */
  protected loaded(name: string): RunningModel | null {
    return this.running().find(r => r.name === name) ?? null;
  }

  /**
   * Agents configured to run this model.
   *
   * <p>Matched on the model name alone. An agent pointed at an endpoint stores a bare `ollama`
   * provider plus a `base_url`, and that url is not part of the profile the dashboard reads —
   * so the honest join available here is by name. Two endpoints serving the same tag therefore
   * both claim the agent, which over-reports rather than hiding a real user.
   */
  protected usedBy(name: string): string[] {
    return this.agents.agents().filter(a => a.model === name).map(a => a.name);
  }

  /** When a load lets go. A pinned model reports no expiry, and ollama dates a pin so far out
   *  that a countdown would read as noise — both say "until stopped" instead. */
  protected expiry(model: RunningModel): string {
    return model.expiresAt && model.expiresAt - Date.now() < EXPIRY_HORIZON_MS
      ? `unloads ${until(model.expiresAt)}`
      : 'loaded until stopped';
  }

  protected gb(bytes: number): string {
    return `${(bytes / 1e9).toFixed(1)} GB`;
  }

  /** On-disk total, which is what an endpoint's models actually cost when idle. */
  protected diskBytes(): number {
    return this.models().reduce((total, model) => total + model.sizeBytes, 0);
  }

  protected vramBytes(): number {
    return this.running().reduce((total, model) => total + model.sizeVramBytes, 0);
  }

  // ── start, stop, pull, remove ───────────────────────────────────────────

  /** Loads or drops one model, then re-reads what is in memory so the row settles on truth
   *  rather than on what was asked for. */
  protected async setLoaded(name: string, loaded: boolean): Promise<void> {
    const id = this.selectedId();
    if (!id || this.busyModel()) return;
    this.busyModel.set(name);
    try {
      await (loaded ? this.providers.loadModel(id, name) : this.providers.unloadModel(id, name));
    } finally {
      if (id === this.selectedId()) this.busyModel.set(null);
    }
    if (id === this.selectedId()) await this.refreshRunning(id);
  }

  protected async pull(): Promise<void> {
    const id = this.selectedId();
    const name = this.pullName.trim();
    if (!id || !name) return;
    this.pullName = '';
    await this.providers.pullModel(id, name);
    // read the state now rather than up to a tick later: the chip is the only sign the pull
    // was accepted. The poller is already running — the pull bar only exists where it does.
    if (id === this.selectedId()) await this.refreshPulls(id);
  }

  protected async removeModel(name: string): Promise<void> {
    const id = this.selectedId();
    if (!id) return;
    this.removingModel.set(null);
    await this.providers.deleteModel(id, name);
    if (id === this.selectedId()) void this.loadModels(id);
  }

  // ── polling ─────────────────────────────────────────────────────────────

  /**
   * One tick of the open panel's live data.
   *
   * <p>Both halves move without the operator: an agent's own call loads a model and expires it
   * again, and a pull reports progress. Polled together rather than on their own timers, so
   * "what is loaded" is as fresh as the page implies whether or not anything is pulling.
   */
  private async poll(id: string): Promise<void> {
    await this.refreshRunning(id);
    await this.refreshPulls(id);
  }

  private async refreshRunning(id: string): Promise<void> {
    const running = await this.providers.running(id);
    if (id === this.selectedId()) this.running.set(running);
  }

  private async refreshPulls(id: string): Promise<void> {
    try {
      const pulls = await this.providers.pullStatus(id);
      if (id !== this.selectedId()) return;
      const wasPulling = this.pulls().some(p => p.status === 'pulling');
      this.pulls.set(pulls);
      // a pull just finished — pick up the new model
      if (wasPulling && !pulls.some(p => p.status === 'pulling')) void this.loadModels(id);
    } catch { /* transient — the next tick is the retry */ }
  }

  private startPolling(id: string): void {
    if (this.pollTimer) return;
    this.pollTimer = setInterval(() => void this.poll(id), POLL_MS);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }
}
