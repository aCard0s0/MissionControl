import gsap from 'gsap';

export const reducedMotion = (): boolean =>
  typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;

/** Tween a numeric value, invoking cb each frame. Instant under reduced motion. */
export function rollNumber(from: number, to: number, cb: (v: number) => void, duration = 0.6): gsap.core.Tween | null {
  if (reducedMotion() || document.hidden || from === to) {
    cb(to);
    return null;
  }
  const proxy = { v: from };
  const tween = gsap.to(proxy, { v: to, duration, ease: 'power2.out', onUpdate: () => cb(proxy.v) });
  // rAF can be throttled (hidden/embedded tabs) — force-finish so values never stall
  setTimeout(() => { if (tween.progress() < 1) tween.progress(1); }, duration * 1000 + 700);
  return tween;
}
