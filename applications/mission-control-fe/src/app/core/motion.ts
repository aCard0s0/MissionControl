export const reducedMotion = (): boolean =>
  typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;

/**
 * Tween a numeric value, invoking `cb` each frame. Instant under reduced motion.
 *
 * <p>A hand-rolled frame loop rather than an animation library: the Web Animations
 * API tweens element properties, and this tweens a *number* on its way into text —
 * which is the one thing WAAPI cannot express.
 *
 * <p>Elapsed time is read from the clock rather than accumulated per frame, so a
 * tab that stops painting mid-roll resumes with the value it should have by then
 * instead of the value it had when it left. That is what the old force-finish
 * timer was for, and the clock removes the need for it.
 */
export function rollNumber(from: number, to: number, cb: (v: number) => void, seconds = 0.6): void {
  const ms = seconds * 1000;
  if (reducedMotion() || document.hidden || from === to || ms <= 0) {
    cb(to);
    return;
  }
  // Timed off the frame clock rather than performance.now(): the two are not
  // guaranteed to share a time origin, and mixing them makes the first frame's
  // elapsed time meaningless. The first frame starts the clock.
  let started: number | null = null;
  const step = (now: number) => {
    started ??= now;
    const t = Math.min(1, (now - started) / ms);
    // ease-out quadratic, the curve the cards were tuned against
    cb(from + (to - from) * (1 - (1 - t) * (1 - t)));
    if (t < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}
