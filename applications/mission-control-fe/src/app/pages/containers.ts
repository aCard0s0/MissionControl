import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { StatusDot } from '../shared/status-dot';
import { Sparkline } from '../shared/sparkline';
import { Reveal } from '../shared/reveal';
import { uptime } from '../core/format';
import { HermesContainer } from '../core/models';

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
  protected deployVersion = 'v0.16.0';
  protected deployProfiles = '';
  protected deployHost = 'dh-local';

  protected readonly addingHost = signal(false);
  protected hostName = '';
  protected hostUrl = 'tcp://';

  protected readonly removing = signal<HermesContainer | null>(null);
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
    this.deployOpen.set(true);
  }

  protected profileCount(id: string): number {
    return this.store.agents().filter(a => a.containerId === id).length;
  }

  protected open(id: string): void {
    this.store.selectContainer(id);
    this.router.navigate(['/overview']);
  }

  protected deploy(): void {
    const name = this.deployName.trim();
    const host = this.store.hostById(this.deployHost);
    if (!name || !host || host.status !== 'connected') return;
    const profiles = this.deployProfiles.split(',').map(p => p.trim()).filter(Boolean);
    const id = this.store.deployContainer(name, this.deployVersion, profiles, this.deployHost);
    this.deployOpen.set(false);
    this.deployName = this.deployProfiles = '';
    if (id) {
      // mock returns the id synchronously; live selects it after the daemon reports it
      this.store.selectContainer(id);
      this.router.navigate(['/overview']);
    }
  }

  protected confirmRemove(): void {
    const c = this.removing();
    if (!c || this.confirmText !== c.name) return;
    this.store.removeContainer(c.id);
    this.removing.set(null);
    this.confirmText = '';
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

  protected hostName_(id: string): string {
    return this.store.hostById(id)?.name ?? '?';
  }
}
