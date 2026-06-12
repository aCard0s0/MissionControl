import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { ModelProvider, OllamaModel } from '../core/models';
import { ApiPullState } from '../core/hermes-api';

@Component({
  selector: 'mc-models',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Reveal],
  templateUrl: './models.html',
  styleUrl: './models.scss',
})
export class ModelsPage {
  protected readonly store = inject(HermesStore);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly ago = ago;

  protected readonly addingProvider = signal(false);
  protected provName = '';
  protected provUrl = '';

  protected readonly selectedId = signal<string | null>(null);
  protected readonly models = signal<OllamaModel[]>([]);
  protected readonly modelsLoading = signal(false);
  protected readonly modelsError = signal<string | null>(null);

  protected pullName = '';
  protected readonly pulls = signal<ApiPullState[]>([]);
  protected readonly removingModel = signal<string | null>(null);

  private pollTimer: ReturnType<typeof setInterval> | null = null;

  private static readonly HTTP_URL = /^https?:\/\/.+/;

  constructor() {
    void this.store.refreshModelProviders();
    this.destroyRef.onDestroy(() => this.stopPolling());
  }

  protected urlValid(): boolean {
    return ModelsPage.HTTP_URL.test(this.provUrl.trim());
  }

  protected addProvider(): void {
    const name = this.provName.trim();
    const url = this.provUrl.trim();
    if (!name || !ModelsPage.HTTP_URL.test(url)) return;
    this.store.addModelProvider(name, url);
    this.addingProvider.set(false);
    this.provName = '';
    this.provUrl = '';
  }

  protected removeProvider(id: string): void {
    if (this.selectedId() === id) {
      this.stopPolling();
      this.selectedId.set(null);
      this.models.set([]);
      this.pulls.set([]);
    }
    this.store.removeModelProvider(id);
  }

  protected selected(): ModelProvider | null {
    const id = this.selectedId();
    return this.store.modelProviders().find(p => p.id === id) ?? null;
  }

  protected select(id: string): void {
    if (this.selectedId() === id) return;
    this.stopPolling();
    this.selectedId.set(id);
    this.models.set([]);
    this.pulls.set([]);
    this.removingModel.set(null);
    this.pullName = '';
    void this.loadModels(id);
    void this.refreshPulls(id);
  }

  protected async loadModels(id: string): Promise<void> {
    this.modelsLoading.set(true);
    this.modelsError.set(null);
    try {
      const models = await this.store.providerModels(id);
      if (id !== this.selectedId()) return;   // provider changed mid-flight — stale response
      this.models.set(models);
    } catch (error) {
      if (id !== this.selectedId()) return;
      const message = error instanceof Error ? error.message : String(error);
      this.modelsError.set(message || 'failed to load models');
      this.models.set([]);
    } finally {
      if (id === this.selectedId()) this.modelsLoading.set(false);
    }
  }

  protected async pull(): Promise<void> {
    const id = this.selectedId();
    const name = this.pullName.trim();
    if (!id || !name) return;
    this.pullName = '';
    await this.store.pullModel(id, name);
    if (id !== this.selectedId()) return;
    await this.refreshPulls(id);
  }

  protected async removeModel(name: string): Promise<void> {
    const id = this.selectedId();
    if (!id) return;
    this.removingModel.set(null);
    await this.store.deleteProviderModel(id, name);
    if (id === this.selectedId()) void this.loadModels(id);
  }

  private async refreshPulls(id: string): Promise<void> {
    try {
      const pulls = await this.store.pullStatus(id);
      if (id !== this.selectedId()) return;
      const wasPulling = this.pulls().some(p => p.status === 'pulling');
      this.pulls.set(pulls);
      if (pulls.some(p => p.status === 'pulling')) {
        this.startPolling(id);
      } else {
        this.stopPolling();
        if (wasPulling) void this.loadModels(id);   // a pull just finished — pick up the new model
      }
    } catch {
      this.stopPolling();
    }
  }

  private startPolling(id: string): void {
    if (this.pollTimer) return;
    this.pollTimer = setInterval(() => void this.refreshPulls(id), 3000);
  }

  private stopPolling(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
      this.pollTimer = null;
    }
  }
}
