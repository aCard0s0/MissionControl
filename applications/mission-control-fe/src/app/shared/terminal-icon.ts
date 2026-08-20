import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * The `>_` glyph every "open a shell here" control wears. Its own component so
 * the places that offer a terminal cannot drift into two different icons.
 *
 * Purely decorative — `aria-hidden`, and the button around it carries the label.
 * It inherits `currentColor`, so it picks up the button's own hover and disabled
 * states rather than defining any of its own.
 */
@Component({
  selector: 'mc-terminal-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <svg viewBox="0 0 16 16" aria-hidden="true">
      <rect x="1.4" y="2.4" width="13.2" height="11.2" rx="1.6" />
      <path d="M4.6 6.6 L6.6 8.6 L4.6 10.6" />
      <path d="M8.4 10.6 H11.4" />
    </svg>`,
  styles: `
    :host { display: inline-flex; }
    svg {
      display: block;
      width: 13px;
      height: 13px;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.4;
      stroke-linecap: round;
      stroke-linejoin: round;
    }`,
})
export class TerminalIcon {}
