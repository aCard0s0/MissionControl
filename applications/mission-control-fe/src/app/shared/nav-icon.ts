import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** The glyphs the sidebar draws, one per destination. */
export const NAV_ICONS = [
  'box', 'chart', 'user', 'layers', 'chip', 'plug',
  'message', 'wrench', 'board', 'calendar', 'bolt', 'terminal', 'doc',
] as const;

export type NavIcon = typeof NAV_ICONS[number];

/**
 * One sidebar destination's glyph, drawn where the nav index used to sit.
 *
 * <p>Its own set rather than {@link AgentIcon}'s: those keys are stored on a
 * blueprint and travel over the wire, so adding a nav-only glyph there would
 * widen a domain type for a decoration.
 *
 * <p>Stroked and inheriting `currentColor`, like {@link TerminalIcon}, so the
 * link's own dim/hover/active colours tint it for free.
 */
@Component({
  selector: 'mc-nav-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './nav-icon.html',
  styles: `
    :host { display: inline-flex; }
    svg {
      display: block;
      width: 100%;
      height: 100%;
      fill: none;
      stroke: currentColor;
      stroke-width: 1.4;
      stroke-linecap: round;
      stroke-linejoin: round;
    }
  `,
})
export class NavIconView {
  readonly icon = input<string>('');

  protected readonly key = computed(() => this.icon() as NavIcon);
}
