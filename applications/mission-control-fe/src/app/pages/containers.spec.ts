import '@angular/compiler';
import { describe, expect, it } from 'vitest';
import { containerUpdate, newerImageTags, normalizeSeedProfiles } from './containers';
import { ImageCatalog, ImageTag } from '../core/models';

describe('normalizeSeedProfiles', () => {
  it('normalizes, deduplicates, and omits the implicit default profile', () => {
    expect(normalizeSeedProfiles(' Default, Ops, research team, ops '))
      .toEqual(['ops', 'research-team']);
  });
});

const HERMES = 'nousresearch/hermes-agent';

const cat = (tags: (string | ImageTag)[], repository = HERMES): ImageCatalog => ({
  repository,
  tags: tags.map(t => typeof t === 'string' ? { tag: t, pulled: true } : t),
  registryStatus: 'ok',
  fetchedAt: 0,
});

const on = (version: string, image = HERMES) => ({ image, version });

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

  it('surfaces a tag the host has not pulled yet, and says so', () => {
    const target = containerUpdate(on('v2026.7.20'), cat([{ tag: 'v2026.8.3', pulled: false }]));
    expect(target).toEqual({ tag: 'v2026.8.3', pulled: false });
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
