import { describe, expect, it } from 'vitest';
import { normalizeSeedProfiles } from './containers';

describe('normalizeSeedProfiles', () => {
  it('normalizes, deduplicates, and omits the implicit default profile', () => {
    expect(normalizeSeedProfiles(' Default, Ops, research team, ops '))
      .toEqual(['ops', 'research-team']);
  });
});
