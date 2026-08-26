import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { liveError, liveNotice, testSlices } from '../../testing/store';

describe('StoreContext toast channels', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('shows a failure, then clears it on its own', async () => {
    const { ctx } = testSlices();

    ctx.toastFailure('deploy', new Error('name already in use'));
    expect(liveError(ctx)).toBe('deploy failed: name already in use');

    await vi.advanceTimersByTimeAsync(6_000);
    expect(liveError(ctx)).toBeNull();
  });

  it('gives a second failure its own full window, not what the first one had left', async () => {
    const { ctx } = testSlices();

    ctx.toast('first');
    await vi.advanceTimersByTimeAsync(5_000);
    ctx.toast('second');

    // the first message's timer would have fired here and blanked the second after 1s
    await vi.advanceTimersByTimeAsync(1_500);
    expect(liveError(ctx)).toBe('second');

    await vi.advanceTimersByTimeAsync(4_500);
    expect(liveError(ctx)).toBeNull();
  });

  it('keeps a confirmation and a failure apart, so neither erases the other', async () => {
    const { ctx } = testSlices();

    ctx.toast('start failed: port bound');
    ctx.notify('container hermes-lab deployed');

    expect(liveError(ctx)).toBe('start failed: port bound');
    expect(liveNotice(ctx)).toBe('container hermes-lab deployed');

    await vi.advanceTimersByTimeAsync(6_000);
    expect(liveError(ctx)).toBeNull();
    expect(liveNotice(ctx)).toBeNull();
  });
});
