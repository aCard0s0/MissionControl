import { WritableSignal, signal } from '@angular/core';

/** Never shorter than this, and never more than this share of the viewport. */
const MIN_PX = 120;
const MAX_VIEWPORT_SHARE = 0.7;

/**
 * A drag-resizable panel height that remembers itself across reloads. Owns the
 * clamp, the persistence and the pointer drag; what to do once the height has
 * settled — refit a terminal, relayout a chart — is the panel's business, so it
 * is passed in per drag.
 */
export class PanelHeight {
  readonly px: WritableSignal<number>;

  constructor(private readonly key: string, private readonly fallback = 280) {
    this.px = signal(this.saved());
  }

  set(px: number): void {
    const clamped = Math.min(
      Math.max(px, MIN_PX), Math.round(window.innerHeight * MAX_VIEWPORT_SHARE));
    this.px.set(clamped);
    try {
      localStorage.setItem(this.key, String(clamped));
    } catch { /* private mode */ }
  }

  bump(delta: number): void {
    this.set(this.px() + delta);
  }

  /** Follows the pointer until it is released; `onSettled` runs once, after. */
  drag(down: PointerEvent, onSettled: () => void): void {
    down.preventDefault();
    const startY = down.clientY;
    const startPx = this.px();
    // dragging the top edge upward makes the panel taller
    const move = (e: PointerEvent) => this.set(startPx + (startY - e.clientY));
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      onSettled();
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  private saved(): number {
    try {
      const saved = Number(localStorage.getItem(this.key));
      if (saved >= MIN_PX) return saved;
    } catch { /* private mode */ }
    return this.fallback;
  }
}
