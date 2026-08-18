import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PanelHeight } from './panel-height';

const KEY = 'mc-test-height';

/** jsdom reports a 768px viewport, so the 70% ceiling lands at 538. */
const MAX = Math.round(768 * 0.7);

describe('PanelHeight', () => {
  beforeEach(() => localStorage.clear());

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('starts from the fallback when nothing has been saved', () => {
    expect(new PanelHeight(KEY, 280).px()).toBe(280);
  });

  it('starts from the height the operator last dragged it to', () => {
    localStorage.setItem(KEY, '420');

    expect(new PanelHeight(KEY, 280).px()).toBe(420);
  });

  it('ignores a saved height that is unusable, rather than opening a sliver', () => {
    localStorage.setItem(KEY, '10');
    expect(new PanelHeight(KEY, 280).px()).toBe(280);

    localStorage.setItem(KEY, 'tall');
    expect(new PanelHeight(KEY, 280).px()).toBe(280);
  });

  it('clamps to a usable minimum and to a share of the viewport', () => {
    const height = new PanelHeight(KEY, 280);

    height.set(10);
    expect(height.px()).toBe(120);

    height.set(5_000);
    expect(height.px()).toBe(MAX);
  });

  it('saves every change under its own key', () => {
    new PanelHeight(KEY, 280).set(300);

    expect(localStorage.getItem(KEY)).toBe('300');
  });

  it('steps by a delta, still within the clamp', () => {
    const height = new PanelHeight(KEY, 280);

    height.bump(80);
    expect(height.px()).toBe(360);

    height.bump(-1_000);
    expect(height.px()).toBe(120);
  });

  it('grows as the drag moves up, and settles once on release', () => {
    const height = new PanelHeight(KEY, 280);
    const settled = vi.fn();

    height.drag(new PointerEvent('pointerdown', { clientY: 500 }), settled);
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 460 }));
    expect(height.px()).toBe(320);
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 520 }));
    expect(height.px()).toBe(260);
    expect(settled).not.toHaveBeenCalled();

    window.dispatchEvent(new PointerEvent('pointerup'));
    expect(settled).toHaveBeenCalledTimes(1);
  });

  it('stops following the pointer once the drag is over', () => {
    const height = new PanelHeight(KEY, 280);

    height.drag(new PointerEvent('pointerdown', { clientY: 500 }), () => { /* settled */ });
    window.dispatchEvent(new PointerEvent('pointerup'));
    window.dispatchEvent(new PointerEvent('pointermove', { clientY: 100 }));

    expect(height.px()).toBe(280);
  });

  it('keeps working when storage refuses the write', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    const height = new PanelHeight(KEY, 280);

    expect(() => height.set(300)).not.toThrow();
    expect(height.px()).toBe(300);
  });
});
