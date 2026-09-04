import { describe, expect, it } from 'vitest';
import { ImageCatalog, ImageTag } from './models';
import {
  containerUpdate, displayVersion, newerImageTags, resolvedVersion, targetVersion, updateTargets,
} from './image-policy';

const HERMES = 'nousresearch/hermes-agent';

type TagSpec = string | (Partial<ImageTag> & { tag: string });

const cat = (tags: TagSpec[], repository = HERMES): ImageCatalog => ({
  repository,
  tags: tags.map(t => typeof t === 'string'
    ? { tag: t, pulled: true, digest: null }
    : { pulled: true, digest: null, ...t }),
  registryStatus: 'ok',
  fetchedAt: 0,
});

const on = (version: string, image = HERMES) => ({ image, version });

/** A container on a floating tag, with the digest of the image it actually runs. */
const onFloating = (version: string, imageDigest: string | null, image = HERMES) =>
  ({ image, version, imageDigest });

describe('resolvedVersion', () => {
  // `latest` is a pointer, not a version. The digest is what turns it back into one.
  it('names the release a floating tag currently points at', () => {
    expect(resolvedVersion(onFloating('latest', 'sha256:aaa'), cat([
      { tag: 'latest', digest: 'sha256:aaa' },
      { tag: 'v2026.8.3', digest: 'sha256:aaa' },
    ]))).toBe('v2026.8.3');
  });

  it('prefers the most specific release sharing the digest', () => {
    // a registry commonly points 1, 1.4 and 1.4.2 at one image; 1.4.2 says the most
    expect(resolvedVersion(onFloating('latest', 'sha256:aaa'), cat([
      { tag: '1', digest: 'sha256:aaa' },
      { tag: '1.4', digest: 'sha256:aaa' },
      { tag: '1.4.2', digest: 'sha256:aaa' },
    ]))).toBe('1.4.2');
  });

  it('never resolves to another floating tag, which would answer nothing', () => {
    expect(resolvedVersion(onFloating('latest', 'sha256:aaa'), cat([
      { tag: 'latest', digest: 'sha256:aaa' },
      { tag: 'edge', digest: 'sha256:aaa' },
    ]))).toBeNull();
  });

  it('leaves a pinned container alone — there is nothing to resolve', () => {
    expect(resolvedVersion(
      { image: HERMES, version: 'v2026.8.3', imageDigest: 'sha256:aaa' },
      cat([{ tag: 'v2026.8.3', digest: 'sha256:aaa' }]))).toBeNull();
  });

  it('gives up rather than guessing when the evidence is missing', () => {
    const registry = cat([{ tag: 'v2026.8.3', digest: 'sha256:aaa' }]);
    // a locally built image has no repo digest to match on
    expect(resolvedVersion(onFloating('latest', null), registry)).toBeNull();
    // an unreachable registry, or a release whose tag has since been deleted
    expect(resolvedVersion(onFloating('latest', 'sha256:aaa'), undefined)).toBeNull();
    expect(resolvedVersion(onFloating('latest', 'sha256:zzz'), registry)).toBeNull();
    // and a catalog for someone else's fork is never consulted
    expect(resolvedVersion(onFloating('latest', 'sha256:aaa'),
      cat([{ tag: 'v9.9.9', digest: 'sha256:aaa' }], 'someone/fork'))).toBeNull();
  });

  it('falls back to the tag for display, which is always truthful', () => {
    expect(displayVersion(onFloating('latest', null), cat(['v1.0.0']))).toBe('latest');
    expect(displayVersion(onFloating('latest', 'sha256:aaa'), cat([
      { tag: 'v1.0.0', digest: 'sha256:aaa' },
    ]))).toBe('v1.0.0');
  });

  it("shows the release hermes itself reports before anything the registry can infer", () => {
    // a `latest` built from main shares its digest with no release tag, so only hermes knows
    expect(displayVersion({ ...onFloating('latest', 'sha256:zzz'), release: '2026.8.19' },
      cat([{ tag: 'latest', digest: 'sha256:zzz' }, { tag: 'v2026.8.31', digest: 'sha256:bbb' }])))
      .toBe('v2026.8.19');
    // no catalog at all is fine — the answer did not come from one
    expect(displayVersion({ ...onFloating('latest', null), release: 'v2026.8.19' }, undefined))
      .toBe('v2026.8.19');
    // a pinned tag already is the version; the release does not second-guess it
    expect(displayVersion({ ...on('v2026.8.19'), release: '2026.8.20' }, undefined)).toBe('v2026.8.19');
    expect(displayVersion({ ...onFloating('latest', null), release: '  ' }, undefined)).toBe('latest');
  });
});

describe('targetVersion', () => {
  it('names where a move along a floating tag actually lands', () => {
    const catalog = cat([
      { tag: 'latest', digest: 'sha256:bbb' },
      { tag: 'v2026.8.3', digest: 'sha256:bbb' },
    ]);
    expect(targetVersion({ tag: 'latest', pulled: true, digest: 'sha256:bbb' }, catalog))
      .toBe('v2026.8.3');
  });

  it('leaves a release target as itself', () => {
    const catalog = cat([{ tag: 'v2026.8.3', digest: 'sha256:bbb' }]);
    expect(targetVersion({ tag: 'v2026.8.3', pulled: true, digest: 'sha256:bbb' }, catalog))
      .toBe('v2026.8.3');
  });

  it('keeps the tag when nothing resolves it', () => {
    expect(targetVersion({ tag: 'latest', pulled: true, digest: null }, cat(['v1.0.0'])))
      .toBe('latest');
    expect(targetVersion({ tag: 'latest', pulled: true, digest: 'sha256:bbb' }, undefined))
      .toBe('latest');
  });
});

describe('containerUpdate on a floating tag', () => {
  // A container on `latest` is never behind by tag string — its tag is always the newest
  // one. The digests are the only evidence, which is why every case below turns on them.
  const registry = (digest: string | null) => cat([{ tag: 'latest', pulled: true, digest }]);

  it('offers the same tag again once the registry has moved it', () => {
    const target = containerUpdate(onFloating('latest', 'sha256:aaa'), registry('sha256:bbb'));

    expect(target?.tag).toBe('latest');
  });

  it('stays quiet while the container runs what the tag points at', () => {
    expect(containerUpdate(onFloating('latest', 'sha256:aaa'), registry('sha256:aaa'))).toBeNull();
  });

  it('claims nothing when either digest is unknown', () => {
    // an image built locally and never pushed has no repo digest …
    expect(containerUpdate(onFloating('latest', null), registry('sha256:bbb'))).toBeNull();
    // … and an air-gapped install or MC_REGISTRY_TAGS=false reports no registry digest
    expect(containerUpdate(onFloating('latest', 'sha256:aaa'), registry(null))).toBeNull();
  });

  it('covers the other tags that track a stream, not just latest', () => {
    for (const tag of ['main', 'edge', 'nightly', 'dev']) {
      const catalog = cat([{ tag, pulled: true, digest: 'sha256:bbb' }]);
      expect(containerUpdate(onFloating(tag, 'sha256:aaa'), catalog)?.tag).toBe(tag);
    }
  });

  it('never answers with another repository\'s tag', () => {
    const foreign = cat([{ tag: 'latest', pulled: true, digest: 'sha256:bbb' }], 'someone/else');

    expect(containerUpdate(onFloating('latest', 'sha256:aaa'), foreign)).toBeNull();
  });

  it('leaves a pinned container to the release rules', () => {
    // a pinned tag is judged by version order, and a digest must not drag it onto a stream
    const catalog = cat([
      { tag: 'latest', pulled: true, digest: 'sha256:bbb' },
      { tag: 'v2026.8.3', pulled: true, digest: null },
    ]);

    expect(containerUpdate(onFloating('v2026.7.20', 'sha256:aaa'), catalog)?.tag)
      .toBe('v2026.8.3');
  });
});

describe('containerUpdate', () => {
  it('offers the newest release and lists every step in between', () => {
    const catalog = cat(['v2026.8.3', 'v2026.7.30', 'v2026.7.20']);
    expect(containerUpdate(on('v2026.7.20'), catalog)?.tag).toBe('v2026.8.3');
    expect(newerImageTags(on('v2026.7.20'), catalog).map(t => t.tag))
      .toEqual(['v2026.8.3', 'v2026.7.30']);
  });

  it('returns null when already on the newest tag', () => {
    expect(containerUpdate(on('v2026.8.3'), cat(['v2026.8.3', 'v2026.7.20']))).toBeNull();
  });

  it('ranks a four-component calendar tag against the release it patches', () => {
    // v2026.7.7.2 is a real published tag; a three-part parser misplaces it
    expect(containerUpdate(on('v2026.7.7'), cat(['v2026.7.7.2']))?.tag).toBe('v2026.7.7.2');
    expect(containerUpdate(on('v2026.7.7.2'), cat(['v2026.7.7']))).toBeNull();
    expect(containerUpdate(on('v2026.7.7.2'), cat(['v2026.7.20']))?.tag).toBe('v2026.7.20');
  });

  it('compares components numerically, not as strings', () => {
    expect(containerUpdate(on('v0.9.0'), cat(['v0.10.0']))?.tag).toBe('v0.10.0');
    expect(containerUpdate(on('v0.10.0'), cat(['v0.9.0']))).toBeNull();
  });

  it('tolerates the v prefix and short forms on either side', () => {
    expect(containerUpdate(on('2026.7.20'), cat(['v2026.7.20']))).toBeNull();
    expect(containerUpdate(on('v1'), cat(['1.0.1']))?.tag).toBe('1.0.1');
  });

  it('never claims a container on a moving or opaque tag is behind', () => {
    for (const version of ['latest', 'main', 'edge', 'sha-9f2c1']) {
      expect(containerUpdate(on(version), cat(['v2026.8.3']))).toBeNull();
    }
  });

  it('never offers latest as a target, because that would un-pin the container', () => {
    expect(containerUpdate(on('v2026.7.20'), cat(['latest']))).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat(['latest', 'v2026.8.3']))?.tag).toBe('v2026.8.3');
  });

  it('skips pre-releases as targets but upgrades a container off one', () => {
    expect(containerUpdate(on('v2026.8.3'), cat(['v2026.8.4-rc1']))).toBeNull();
    expect(containerUpdate(on('v2026.8.3-rc1'), cat(['v2026.8.3']))?.tag).toBe('v2026.8.3');
  });

  it('orders two pre-releases of the same version by their marker', () => {
    // only reachable through the ranking itself — neither is ever offered as a
    // target, but a container on rc1 must still see rc2 as the newer build
    expect(newerImageTags(on('v2026.8.3-rc1'), cat(['v2026.8.3-rc2', 'v2026.8.3-rc0']))).toEqual([]);
    expect(newerImageTags(on('v2026.8.3-rc1'), cat(['v2026.8.4', 'v2026.8.3'])).map(t => t.tag))
      .toEqual(['v2026.8.4', 'v2026.8.3']);
  });

  it('surfaces a tag the host has not pulled yet, and says so', () => {
    const target = containerUpdate(on('v2026.7.20'), cat([{ tag: 'v2026.8.3', pulled: false }]));
    expect(target).toEqual({ tag: 'v2026.8.3', pulled: false, digest: null });
  });

  it('computes the maximum from an unsorted catalog', () => {
    const catalog = cat(['v2026.7.30', 'v2026.8.3', 'v2026.4.3']);
    expect(containerUpdate(on('v2026.7.20'), catalog)?.tag).toBe('v2026.8.3');
    expect(newerImageTags(on('v2026.7.20'), catalog).map(t => t.tag))
      .toEqual(['v2026.8.3', 'v2026.7.30']);
  });

  it('ignores a catalog belonging to another repository', () => {
    expect(containerUpdate(on('v2026.7.20', 'acme/hermes-fork'), cat(['v2026.8.3']))).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat(['v2026.8.3'], 'docker.io/nousresearch/hermes-agent'))?.tag)
      .toBe('v2026.8.3');
  });

  it('returns nothing for a missing or empty catalog', () => {
    expect(containerUpdate(on('v2026.7.20'), undefined)).toBeNull();
    expect(containerUpdate(on('v2026.7.20'), cat([]))).toBeNull();
  });
});

// `updateTargets` is the list behind containerUpdate's single answer. It was a private on the
// containers page, so nothing tested it directly — only the modal that renders it did.
describe('updateTargets', () => {
  it('offers every newer release, newest first', () => {
    expect(updateTargets(on('v2026.7.7'), cat(['v2026.8.3', 'v2026.7.20', 'v2026.7.7']))
      .map(t => t.tag)).toEqual(['v2026.8.3', 'v2026.7.20']);
  });

  it('offers the floating tag itself when there is no newer release to move to', () => {
    const catalog = cat([{ tag: 'latest', digest: 'sha256:bbb' }]);
    expect(updateTargets(onFloating('latest', 'sha256:aaa'), catalog).map(t => t.tag))
      .toEqual(['latest']);
  });

  // a re-pull is only worth offering when the registry has actually moved the tag
  it('offers nothing for a floating tag the registry has not moved', () => {
    expect(updateTargets(onFloating('latest', 'sha256:aaa'),
      cat([{ tag: 'latest', digest: 'sha256:aaa' }]))).toEqual([]);
  });

  // a pinned container is answered by tag comparison alone, so the floating branch is never
  // reached — moving it onto `latest` would silently un-pin it
  it('never offers a floating tag to a container running a pinned one', () => {
    const catalog = cat([
      { tag: 'latest', digest: 'sha256:bbb' },
      { tag: 'v2026.8.3', digest: 'sha256:bbb' },
    ]);
    expect(updateTargets(on('v2026.7.7'), catalog).map(t => t.tag)).toEqual(['v2026.8.3']);
  });

  it('offers nothing when the catalog is for another repository', () => {
    expect(updateTargets(on('v1.0.0'), cat(['v2.0.0'], 'someone/fork'))).toEqual([]);
  });
});
