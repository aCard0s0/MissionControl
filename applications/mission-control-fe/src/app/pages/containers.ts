import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AgentStore } from '../core/store/agent-store';
import { ContainerLifecycle } from '../core/store/container-lifecycle';
import { ContainerStore } from '../core/store/container-store';
import { HostStore } from '../core/store/host-store';
import { ImageCatalogStore } from '../core/store/image-catalog-store';
import { StoreContext } from '../core/store/store-context';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { StatusDot } from '../shared/status-dot';
import { Sparkline } from '../shared/sparkline';
import { Reveal } from '../shared/reveal';
import { TerminalIcon } from '../shared/terminal-icon';
import { errorMessage } from '../core/errors';
import { uptime } from '../core/format';
import {
  containerUpdate, displayVersion, isFloatingTag, targetVersion, updateTargets,
} from '../core/image-policy';
import { HermesContainer, ImageTag } from '../core/models';

export function normalizeSeedProfiles(value: string): string[] {
  return Array.from(new Set(value.split(',')
    .map(p => p.trim().toLowerCase().replace(/\s+/g, '-'))
    .filter(p => !!p && p !== 'default')));
}

@Component({
  selector: 'mc-containers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot, Sparkline, Reveal, TerminalIcon],
  templateUrl: './containers.html',
  styleUrl: './containers.scss',
})
export class ContainersPage {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly ctx = inject(StoreContext);
  protected readonly hosts = inject(HostStore);
  protected readonly images = inject(ImageCatalogStore);
  protected readonly lifecycle = inject(ContainerLifecycle);
  protected readonly terminal = inject(TerminalRequestStore);
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

  /** Set when a deploy came back empty, so the modal stays and says why. */
  protected readonly deployFailed = signal(false);

  protected readonly addingHost = signal(false);
  protected hostName = '';
  protected hostUrl = 'tcp://';

  protected readonly removing = signal<HermesContainer | null>(null);
  protected readonly removingBusy = signal(false);
  protected confirmText = '';

  protected readonly updating = signal<HermesContainer | null>(null);
  protected readonly updatingBusy = signal(false);
  protected readonly updateTargets = signal<ImageTag[]>([]);
  protected updateVersion = '';

  protected readonly connectedHosts = computed(() =>
    this.hosts.hosts().filter(h => h.status === 'connected'));

  /** containerId → the newest release it could move to. */
  protected readonly updates = computed(() => {
    const catalogs = this.images.catalog();
    const map = new Map<string, ImageTag>();
    for (const c of this.containers.containers()) {
      const target = containerUpdate(c, catalogs[c.hostId]);
      if (target) map.set(c.id, target);
    }
    return map;
  });

  constructor() {
    // fresh on navigate; the store's TTL collapses this with its own poll
    void this.images.refreshAll();
  }

  protected isUpdating(id: string): boolean {
    return this.updatingBusy() && this.updating()?.id === id;
  }

  /**
   * Open the bottom terminal panel on a shell in this container. No command: the
   * operator asked for a prompt, not for something to be run in it. A repeat
   * click focuses the tab this container already has rather than stacking one.
   */
  protected openTerminal(c: HermesContainer): void {
    this.terminal.open({ hostId: c.hostId, containerId: c.id, label: c.name });
  }

  /** The version to show for this container — the release it runs, not the pointer. */
  protected version(c: HermesContainer): string {
    return displayVersion(c, this.images.catalog()[c.hostId]);
  }

  /** True when the container tracks a moving tag, so the card can still say which. */
  protected tracks(c: HermesContainer): string | null {
    return isFloatingTag(c.version) && this.version(c) !== c.version ? c.version : null;
  }

  /** What the update moves it to, named as the release rather than the tag. */
  protected targetLabel(c: HermesContainer, target: ImageTag): string {
    return targetVersion(target, this.images.catalog()[c.hostId]);
  }

  protected updateHint(c: HermesContainer, target: ImageTag): string {
    const from = this.version(c);
    const to = this.targetLabel(c, target);
    // both ends resolved, so a move along a floating tag reads as the version change it is
    const move = from === to
      ? `${from} · the registry published a new image on ${c.version}`
      : `${from} → ${to}`;
    const via = isFloatingTag(target.tag) && to !== target.tag ? ` · on ${target.tag}` : '';
    return `${move}${via}${target.pulled ? '' : ' · not pulled on this host yet'}`;
  }

  /** A target option, naming the release a moving tag currently points at. */
  protected optionLabel(c: HermesContainer, t: ImageTag): string {
    const release = targetVersion(t, this.images.catalog()[c.hostId]);
    const name = release === t.tag ? t.tag : `${t.tag} — ${release}`;
    return `${name}${t.pulled ? '' : ' — not pulled'}`;
  }

  /** Everything this container could move to: newer releases, or the same tag re-pulled. */
  private targetsFor(c: HermesContainer): ImageTag[] {
    return updateTargets(c, this.images.catalog()[c.hostId]);
  }

  /**
   * What the backend is doing right now, in the operator's words.
   *
   * <p>An update on a cold host pulls an image before it recreates anything, which is minutes
   * of a spinner with nothing to read. Naming the slow half is the difference between "it is
   * working" and "it is stuck".
   */
  protected updateStage(): string {
    return this.targetPulled() ? 'recreating the container' : 'pulling the image, then recreating';
  }

  protected targetPulled(): boolean {
    return this.updateTargets().find(t => t.tag === this.updateVersion)?.pulled ?? true;
  }

  protected beginUpdate(c: HermesContainer): void {
    const targets = this.targetsFor(c);
    if (!targets.length || this.updatingBusy()) return;
    this.updateTargets.set(targets);
    this.updateVersion = targets[0].tag;
    this.updating.set(c);
  }

  protected cancelUpdate(): void {
    if (this.updatingBusy()) return;
    this.updating.set(null);
    this.updateTargets.set([]);
    this.updateVersion = '';
  }

  protected async confirmUpdate(): Promise<void> {
    const c = this.updating();
    if (!c || !this.updateVersion || this.updatingBusy()) return;
    this.updatingBusy.set(true);
    try {
      if (await this.lifecycle.update(c.id, this.updateVersion)) {
        this.updating.set(null);
        this.updateTargets.set([]);
        this.updateVersion = '';
      }
    } finally {
      this.updatingBusy.set(false);
    }
  }

  private static readonly TCP_URL = /^tcp:\/\/.+:\d+$/;

  protected hostUrlValid(): boolean {
    return ContainersPage.TCP_URL.test(this.hostUrl.trim());
  }

  protected openDeploy(): void {
    // never carry a stale host id into the modal — snap to a connected host
    this.deployFailed.set(false);
    this.deployHost = this.connectedHosts()[0]?.id ?? '';
    this.deployTags.set([]);
    this.tagsError.set(null);
    this.tagsLoading.set(false);
    this.deployOpen.set(true);
    void this.loadTags(this.deployHost);
  }

  protected profileCount(id: string): number {
    return this.agents.agents().filter(a => a.containerId === id).length;
  }

  protected open(id: string): void {
    this.containers.select(id);
    this.router.navigate(['/overview']);
  }

  protected async deploy(): Promise<void> {
    const name = this.deployName.trim();
    const host = this.hosts.byId(this.deployHost);
    if (!name || !host || host.status !== 'connected' || !this.deployVersion || this.deployBusy()) return;
    const profiles = normalizeSeedProfiles(this.deployProfiles);
    this.deployBusy.set(true);
    this.deployFailed.set(false);
    const id = await this.lifecycle.deploy(name, this.deployVersion, profiles, this.deployHost);
    this.deployBusy.set(false);
    if (!id) {
      this.deployFailed.set(true);
      return;
    }
    // Following the new container onto Overview is right for an operator who waited on the
    // modal, and wrong for one who closed it and walked to another page — a pull can take
    // minutes, and yanking them off whatever they went to do is not a reward for waiting.
    const waiting = this.deployOpen();
    this.deployOpen.set(false);
    this.deployName = '';
    this.deployProfiles = '';
    this.deployTags.set([]);
    this.containers.select(id);
    if (waiting) this.router.navigate(['/overview']);
  }

  protected async confirmRemove(): Promise<void> {
    const c = this.removing();
    if (!c || this.confirmText !== c.name || this.removingBusy()) return;
    this.removingBusy.set(true);
    const removed = await this.lifecycle.remove(c.id);
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
    this.hosts.add(name, url);
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
      const { tags } = await this.images.tags(hostId);
      if (hostId !== this.deployHost) return;   // host changed mid-flight — stale response
      this.deployTags.set(tags);
      if (!tags.includes(this.deployVersion)) {
        this.deployVersion = tags.includes('latest') ? 'latest' : (tags[0] ?? '');
      }
    } catch (error) {
      if (hostId !== this.deployHost) return;
      this.tagsError.set(errorMessage(error, 'failed to load image tags'));
      this.deployTags.set([]);
      this.deployVersion = '';
    } finally {
      this.tagsLoading.set(false);
    }
  }

  /** The docker host's display name, or '?' when it is no longer in the list. */
  protected hostLabel(id: string): string {
    return this.hosts.byId(id)?.name ?? '?';
  }
}
