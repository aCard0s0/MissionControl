import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Sparkline } from '../shared/sparkline';
import { Reveal } from '../shared/reveal';
import { uptime } from '../core/format';
import { HermesContainer } from '../core/models';

export function normalizeSeedProfiles(value: string): string[] {
  return Array.from(new Set(value.split(',')
    .map(p => p.trim().toLowerCase().replace(/\s+/g, '-'))
    .filter(p => !!p && p !== 'default')));
}

@Component({
  selector: 'mc-containers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Sparkline, Reveal],
  templateUrl: './containers.html',
  styleUrl: './containers.scss',
})
export class ContainersPage {
  protected readonly store = inject(HermesStore);
  private readonly router = inject(Router);

  protected readonly uptime = uptime;

  protected readonly deployOpen = signal(false);
  protected deployName = '';
  protected deployVersion = '';
  protected deployProfiles = '';
  protected deployHost = 'dh-local';
  protected readonly deployTags = signal<string[]>([]);
  protected readonly tagsLoading = signal(false);
  protected readonly tagsError = signal<string | null>(null);
  protected readonly deployBusy = signal(false);

  protected readonly addingHost = signal(false);
  protected hostName = '';
  protected hostUrl = 'tcp://';

  protected readonly removing = signal<HermesContainer | null>(null);
  protected readonly removingBusy = signal(false);
  protected confirmText = '';

  protected readonly connectedHosts = computed(() =>
    this.store.dockerHosts().filter(h => h.status === 'connected'));

  private static readonly TCP_URL = /^tcp:\/\/.+:\d+$/;

  protected hostUrlValid(): boolean {
    return ContainersPage.TCP_URL.test(this.hostUrl.trim());
  }

  protected openDeploy(): void {
    // never carry a stale host id into the modal — snap to a connected host
    this.deployHost = this.connectedHosts()[0]?.id ?? '';
    this.deployTags.set([]);
    this.tagsError.set(null);
    this.tagsLoading.set(false);
    this.deployOpen.set(true);
    void this.loadTags(this.deployHost);
  }

  protected profileCount(id: string): number {
    return this.store.agents().filter(a => a.containerId === id).length;
  }

  protected open(id: string): void {
    this.store.selectContainer(id);
    this.router.navigate(['/overview']);
  }

  protected async deploy(): Promise<void> {
    const name = this.deployName.trim();
    const host = this.store.hostById(this.deployHost);
    if (!name || !host || host.status !== 'connected' || !this.deployVersion || this.deployBusy()) return;
    const profiles = normalizeSeedProfiles(this.deployProfiles);
    this.deployBusy.set(true);
    const id = await this.store.deployContainer(name, this.deployVersion, profiles, this.deployHost);
    this.deployBusy.set(false);
    if (id) {
      this.deployOpen.set(false);
      this.deployName = this.deployProfiles = '';
      this.deployTags.set([]);
      this.store.selectContainer(id);
      this.router.navigate(['/overview']);
    }
  }

  protected async confirmRemove(): Promise<void> {
    const c = this.removing();
    if (!c || this.confirmText !== c.name || this.removingBusy()) return;
    this.removingBusy.set(true);
    const removed = await this.store.removeContainer(c.id);
    this.removingBusy.set(false);
    if (removed) {
      this.removing.set(null);
      this.confirmText = '';
    }
  }

  protected addHost(): void {
    const name = this.hostName.trim();
    const url = this.hostUrl.trim();
    if (!name || !ContainersPage.TCP_URL.test(url)) return;
    this.store.addDockerHost(name, url);
    this.addingHost.set(false);
    this.hostName = '';
    this.hostUrl = 'tcp://';
  }

  protected async loadTags(hostId: string): Promise<void> {
    if (!hostId) {
      this.deployTags.set([]);
      this.deployVersion = '';
      return;
    }
    this.tagsLoading.set(true);
    this.tagsError.set(null);
    try {
      const { tags } = await this.store.imageTags(hostId);
      if (hostId !== this.deployHost) return;   // host changed mid-flight — stale response
      this.deployTags.set(tags);
      if (!tags.includes(this.deployVersion)) {
        this.deployVersion = tags.includes('latest') ? 'latest' : (tags[0] ?? '');
      }
    } catch (error) {
      if (hostId !== this.deployHost) return;
      const message = error instanceof Error ? error.message : String(error);
      this.tagsError.set(message || 'failed to load image tags');
      this.deployTags.set([]);
      this.deployVersion = '';
    } finally {
      this.tagsLoading.set(false);
    }
  }

  protected hostName_(id: string): string {
    return this.store.hostById(id)?.name ?? '?';
  }
}
