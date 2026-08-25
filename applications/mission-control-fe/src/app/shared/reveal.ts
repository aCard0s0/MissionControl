import { Directive, ElementRef, afterNextRender, inject } from '@angular/core';
import { reducedMotion } from '../core/motion';

// Stagger index shared by all directives instantiated in the same render pass,
// so batches (page loads, tab switches) cascade while singletons appear at once.
let batch = 0;
let resetQueued = false;

/** Attach to any element via `data-reveal` — it fades/slides itself in on creation. */
// A data attribute on purpose: templates opt in with plain `data-reveal`, so the
// selector cannot be the camelCase input name the rule expects.
// eslint-disable-next-line @angular-eslint/directive-selector
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

    // Hidden synchronously, because the element is in the DOM a frame before the
    // animation below can measure it — without this it flashes at full opacity first.
    this.el.style.visibility = 'hidden';
    afterNextRender(() => {
      this.el.style.visibility = '';
      // `fill: backwards` holds the first keyframe through the stagger delay, which is
      // what the old library call needed a separate hidden state for. Nothing has to
      // clear the properties afterwards either: a finished animation stops applying
      // them on its own, where clearProps had to be asked for.
      this.el.animate(
        [{ opacity: 0, transform: 'translateY(14px)' }, { opacity: 1, transform: 'none' }],
        {
          duration: 500,
          delay: index * 55,
          easing: 'cubic-bezier(0.16, 1, 0.3, 1)',
          fill: 'backwards',
        },
      );
    });
  }
}
