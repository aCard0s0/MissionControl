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
import { HermesContainer, ImageCatalog, ImageTag } from '../core/models';

export function normalizeSeedProfiles(value: string): string[] {
  return Array.from(new Set(value.split(',')
    .map(p => p.trim().toLowerCase().replace(/\s+/g, '-'))
    .filter(p => !!p && p !== 'default')));
}

interface Ver { readonly nums: readonly number[]; readonly pre: string | null; }

// v-prefix optional; any number of numeric components, because Hermes publishes
// calendar tags like v2026.7.7.2. Anything after the first '-' or '+' is a
// pre-release marker.
const TAG = /^v?(\d+(?:\.\d+)*)(?:[-+](.+))?$/i;

function parseTag(tag: string): Ver | null {
  const m = TAG.exec(tag.trim());
  return m ? { nums: m[1].split('.').map(Number), pre: m[2] ?? null } : null;
}

function compareVer(a: Ver, b: Ver): number {
  const length = Math.max(a.nums.length, b.nums.length);
  for (let i = 0; i < length; i++) {
    const l = a.nums[i] ?? 0;
    const r = b.nums[i] ?? 0;
    if (l !== r) return l < r ? -1 : 1;
  }
  if (a.pre === b.pre) return 0;
  if (a.pre === null) return 1;      // a release outranks any pre-release of the same number
  if (b.pre === null) return -1;
  return a.pre < b.pre ? -1 : a.pre > b.pre ? 1 : 0;
}

/** Tags that track a stream rather than pinning a release — mirrors ImageRef.FLOATING. */
const FLOATING_TAGS = new Set(['latest', 'main', 'edge', 'nightly', 'dev']);

function isFloatingTag(tag: string): boolean {
  return FLOATING_TAGS.has(tag.trim().toLowerCase());
}

function sameRepository(a: string, b: string): boolean {
  const norm = (r: string) =>
    r.trim().toLowerCase().replace(/^docker\.io\//, '').replace(/^library\//, '');
  return !!a?.trim() && !!b?.trim() && norm(a) === norm(b);
}

/**
 * Every tag in `catalog` that is a strictly newer *release* of the image this
 * container already runs, newest first.
 *
 * Each refusal below is a claim we cannot honestly make from tag strings alone:
 *  - a container on `latest`/`main` — or on any tag we can't parse, like a
 *    `sha-9f2c1` build — is never "behind": the tag is a moving pointer and the
 *    frontend has no image digest to compare against;
 *  - `latest` is never an update *target*, because moving a pinned container
 *    onto it would silently un-pin it;
 *  - pre-releases are never targets, but a container already running one is
 *    correctly offered the matching release;
 *  - a catalog for a different repository is ignored outright, so a fork is
 *    never handed Hermes' tags.
 */
/** What the update rules need off a container — the digest optional, because the
 *  tag-only rules below never look at it. */
type UpdateCandidate = Pick<HermesContainer, 'image' | 'version'> & {
  readonly imageDigest?: string | null;
};

export function newerImageTags(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): ImageTag[] {
  if (!catalog || !sameRepository(container.image, catalog.repository)) return [];
  const current = parseTag(container.version);
  if (!current) return [];
  return catalog.tags
    .map(entry => ({ entry, ver: entry.tag === 'latest' ? null : parseTag(entry.tag) }))
    .filter((x): x is { entry: ImageTag; ver: Ver } =>
      !!x.ver && x.ver.pre === null && compareVer(x.ver, current) > 0)
    .sort((a, b) => compareVer(b.ver, a.ver))
    .map(x => x.entry);
}

/**
 * The same floating tag, when the registry has moved it somewhere this container is not.
 *
 * A container on `latest` can never be "behind" by tag string — its tag is always the newest
 * one. The digests are the only evidence: the registry's manifest digest for that tag against
 * the digest of the image the container actually runs. Both have to be known, so a locally
 * built image (no repo digest), an air-gapped install and `MC_REGISTRY_TAGS=false` all keep
 * today's silence rather than inventing a prompt nobody can act on.
 */
export function floatingUpdate(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): ImageTag | null {
  if (!catalog || !sameRepository(container.image, catalog.repository)) return null;
  if (!isFloatingTag(container.version) || !container.imageDigest) return null;
  const entry = catalog.tags.find(t => t.tag === container.version);
  if (!entry?.digest) return null;
  return entry.digest === container.imageDigest ? null : entry;
}

/** The newest release this container could move to, or null when it is current. */
export function containerUpdate(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): ImageTag | null {
  return newerImageTags(container, catalog)[0] ?? floatingUpdate(container, catalog);
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

  protected updateHint(c: HermesContainer, target: ImageTag): string {
    // a floating tag moves in place, so "latest → latest" would read as a no-op
    const move = target.tag === c.version
      ? `${c.version} · the registry published a new image on this tag`
      : `${c.version} → ${target.tag}`;
    return `${move}${target.pulled ? '' : ' · not pulled on this host yet'}`;
  }

  /** Everything this container could move to: newer releases, or the same tag re-pulled. */
  private targetsFor(c: HermesContainer): ImageTag[] {
    const catalog = this.images.catalog()[c.hostId];
    const newer = newerImageTags(c, catalog);
    if (newer.length) return newer;
    const floating = floatingUpdate(c, catalog);
    return floating ? [floating] : [];
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
    const id = await this.lifecycle.deploy(name, this.deployVersion, profiles, this.deployHost);
    this.deployBusy.set(false);
    if (id) {
      this.deployOpen.set(false);
      this.deployName = '';
      this.deployProfiles = '';
      this.deployTags.set([]);
      this.containers.select(id);
      this.router.navigate(['/overview']);
    }
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
