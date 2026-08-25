import { afterEach, describe, expect, it, vi } from 'vitest';
import { rollNumber } from './motion';

const matchMedia = (reduce: boolean) =>
  vi.stubGlobal('matchMedia', () => ({ matches: reduce }));

/** Resolves once the tween has handed `cb` its final value. */
const settled = (seen: number[], to: number) => new Promise<void>((resolve, reject) => {
  const deadline = Date.now() + 2000;
  const poll = () => {
    if (seen.at(-1) === to) return resolve();
    if (Date.now() > deadline) return reject(new Error(`never reached ${to}: ${seen.join()}`));
    requestAnimationFrame(poll);
  };
  poll();
});

describe('rollNumber', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('lands exactly on the target rather than near it', async () => {
    matchMedia(false);
    const seen: number[] = [];

    rollNumber(0, 42, v => seen.push(v), 0.02);
    await settled(seen, 42);

    expect(seen.at(-1)).toBe(42);
  });

  it('moves toward the target rather than jumping, and never overshoots', async () => {
    matchMedia(false);
    const seen: number[] = [];

    rollNumber(0, 100, v => seen.push(v), 0.05);
    await settled(seen, 100);

    expect(Math.max(...seen)).toBe(100);
    expect(Math.min(...seen)).toBeGreaterThanOrEqual(0);
    // monotonic: an eased tween that went backwards would read as a glitching counter
    expect([...seen].sort((a, b) => a - b)).toEqual(seen);
  });

  it('is instant when the operator asked the OS for less motion', () => {
    matchMedia(true);
    const seen: number[] = [];

    rollNumber(0, 42, v => seen.push(v));

    expect(seen).toEqual([42]);
  });

  it('does not animate a value that has not changed', () => {
    matchMedia(false);
    const seen: number[] = [];

    rollNumber(7, 7, v => seen.push(v));

    expect(seen).toEqual([7]);
  });

  it('is instant for a zero-length roll rather than dividing by it', () => {
    matchMedia(false);
    const seen: number[] = [];

    rollNumber(0, 5, v => seen.push(v), 0);

    expect(seen).toEqual([5]);
  });
});
