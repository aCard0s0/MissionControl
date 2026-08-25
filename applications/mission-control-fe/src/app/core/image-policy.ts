import { HermesContainer, ImageCatalog, ImageTag } from './models';

/**
 * What a container's image tag means, and which one it could move to.
 *
 * <p>Lives in core because more than one layer has to agree on it: the containers page decides
 * whether to offer an update, the overview repeats that answer on the page an operator lands
 * on, and {@link ContainerLifecycle} decides whether the request is worth sending. They
 * disagreed once — the page offered `latest → latest` and the store dropped it as a no-op — so
 * the button did nothing at all. Everything below is the whole of that decision, in one place,
 * so a fourth caller cannot re-derive half of it.
 *
 * <p>Pure functions over a container and a catalog. Nothing here reads a store or a signal:
 * every rule is a claim about two strings and a digest, and keeping it that way is what lets
 * the page, the overview and the store ask the same question without sharing anything else.
 */

/** Tags that move. Mirrors `ImageRef.FLOATING` on the backend. */
const FLOATING_TAGS = new Set(['latest', 'main', 'edge', 'nightly', 'dev']);

export function isFloatingTag(tag: string): boolean {
  return FLOATING_TAGS.has((tag ?? '').trim().toLowerCase());
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

function sameRepository(a: string, b: string): boolean {
  const norm = (r: string) =>
    r.trim().toLowerCase().replace(/^docker\.io\//, '').replace(/^library\//, '');
  return !!a?.trim() && !!b?.trim() && norm(a) === norm(b);
}

/** What the update rules need off a container — the digest optional, because the
 *  tag-only rules below never look at it. */
export type UpdateCandidate = Pick<HermesContainer, 'image' | 'version'> & {
  readonly imageDigest?: string | null;
};

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
function floatingUpdate(
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

/**
 * Everything this container could move to: newer releases, or the same tag re-pulled.
 *
 * <p>The list behind {@link containerUpdate}'s single answer — what an update dialog offers
 * once an operator has decided to act on the prompt.
 */
export function updateTargets(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): ImageTag[] {
  const newer = newerImageTags(container, catalog);
  if (newer.length) return newer;
  const floating = floatingUpdate(container, catalog);
  return floating ? [floating] : [];
}

/**
 * The release a container is actually running, when it was started from a tag that moves.
 *
 * <p>`latest` is a pointer, not a version, and showing it as one is what made an "update
 * latest" button read as a no-op: the card claimed the container was on the newest thing while
 * the button said otherwise. Both were true — the tag was newest, the image behind it was not.
 * The digest is what resolves that: whichever release tag the registry published at the same
 * digest is what this container is really on.
 *
 * <p>Null whenever that cannot be established rather than guessed — a tag that does not move
 * needs no resolving, and a locally built image, an unreachable registry or a release whose
 * tag has since been deleted all leave the pointer as the only honest answer.
 */
export function resolvedVersion(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): string | null {
  if (!isFloatingTag(container.version)) return null;
  if (!catalog || !sameRepository(container.image, catalog.repository)) return null;
  if (!container.imageDigest) return null;
  return releaseAt(container.imageDigest, catalog);
}

/** What to show as a container's version: the release it runs, else the tag it was given. */
export function displayVersion(
  container: UpdateCandidate,
  catalog: ImageCatalog | undefined,
): string {
  return resolvedVersion(container, catalog) ?? container.version;
}

/**
 * The release an update target actually is.
 *
 * <p>Same resolution as {@link resolvedVersion}, applied to where the container is going
 * rather than where it is — so a move along a floating tag can be offered as the version
 * change it really is instead of as `latest → latest`. Falls back to the tag, which is always
 * a truthful thing to call it.
 */
export function targetVersion(target: ImageTag, catalog: ImageCatalog | undefined): string {
  if (!catalog || !isFloatingTag(target.tag) || !target.digest) return target.tag;
  return releaseAt(target.digest, catalog) ?? target.tag;
}

/**
 * The release tag published at `digest`, preferring the most specific one.
 *
 * <p>A registry commonly points `1`, `1.4` and `1.4.2` at one image. `1.4.2` is the answer a
 * reader wants — it says the most — so tags are ranked by how many components they carry
 * before falling back to comparing versions.
 */
function releaseAt(digest: string, catalog: ImageCatalog): string | null {
  const releases = catalog.tags
    .filter(t => !!t.digest && t.digest === digest && !isFloatingTag(t.tag))
    .map(t => ({ tag: t.tag, ver: parseTag(t.tag) }))
    .filter((x): x is { tag: string; ver: Ver } => !!x.ver);
  if (!releases.length) return null;
  releases.sort((a, b) => b.ver.nums.length - a.ver.nums.length || compareVer(b.ver, a.ver));
  return releases[0].tag;
}
