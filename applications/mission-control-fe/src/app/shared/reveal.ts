import { Directive, ElementRef, afterNextRender, inject } from '@angular/core';
import gsap from 'gsap';
import { reducedMotion } from '../core/motion';

// Stagger index shared by all directives instantiated in the same render pass,
// so batches (page loads, tab switches) cascade while singletons appear at once.
let batch = 0;
let resetQueued = false;

/** Attach to any element via `data-reveal` — it fades/slides itself in on creation. */
@Directive({ selector: '[data-reveal]' })
export class Reveal {
  private readonly el: HTMLElement = inject(ElementRef).nativeElement;

  constructor() {
    if (reducedMotion() || document.hidden) return;

    const index = batch++;
    if (!resetQueued) {
      resetQueued = true;
      queueMicrotask(() => { batch = 0; resetQueued = false; });
    }

    this.el.style.visibility = 'hidden';
    afterNextRender(() => {
      const tween = gsap.fromTo(
        this.el,
        { autoAlpha: 0, y: 14 },
        {
          autoAlpha: 1, y: 0, duration: 0.5, ease: 'power3.out',
          delay: index * 0.055, clearProps: 'visibility,opacity,transform',
        },
      );
      // rAF can be throttled (hidden/embedded tabs) — never leave content invisible
      setTimeout(() => { if (tween.progress() < 1) tween.progress(1); }, 1400 + index * 55);
    });
  }
}
