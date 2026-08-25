import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * The glyphs a blueprint can wear, in the order the picker offers them.
 *
 * <p>Keys, not images: the value is stored on the blueprint and sent over the
 * wire, so it has to survive a redraw of the artwork. A blueprint written when
 * `radar` looked one way keeps its meaning when the path changes.
 */
export const AGENT_ICONS = [
  'terminal', 'shield', 'beaker', 'chart', 'radar', 'bug',
  'book', 'bolt', 'eye', 'gear', 'message', 'spark',
] as const;

export type AgentIcon = typeof AGENT_ICONS[number];

const KNOWN = new Set<string>(AGENT_ICONS);

/** True for a key this component can actually draw. */
export const isAgentIcon = (key: string | null | undefined): key is AgentIcon =>
  !!key && KNOWN.has(key);

/**
 * One blueprint's glyph, drawn beside its name in the list and the editor.
 *
 * <p>Stroked and inheriting `currentColor`, like {@link TerminalIcon}, so it
 * takes the colour of whatever it sits in rather than carrying its own — a
 * selected card, a hovered row and the editor heading all tint it for free.
 *
 * <p>An unset or unrecognised key falls back to `spark` rather than rendering
 * nothing: a blueprint saved before the library had glyphs, or one carrying a
 * key from a later version, still lines up with its neighbours in the list.
 */
@Component({
  selector: 'mc-agent-icon',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './agent-icon.html',
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
export class AgentIconView {
  readonly icon = input<string>('');

  protected readonly key = computed<AgentIcon>(() => {
    const wanted = this.icon();
    return isAgentIcon(wanted) ? wanted : 'spark';
  });
}
