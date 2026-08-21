import { EffectRef, Injector, effect } from '@angular/core';
import { TerminalSession } from './terminal-session';

/**
 * The strip along the bottom of a pane that says its grid is wider than its box.
 *
 * <p>This used to be written into the terminal itself, which put it where the operator was
 * looking at the cost of putting it everywhere else too: into the scrollback they copy, into a
 * stream that tools parse, and — leading CRLF and all — across whatever line the shell was
 * mid-way through drawing. As chrome it says the same thing, stays out of the buffer, and can
 * carry the button that fixes it, so the way out stops being folklore about which toolbar
 * glyph to press.
 *
 * <p>Plain DOM for the same reason {@link TerminalTabView} is: the pane it sits in is built by
 * dockview, so nothing in it would carry an Angular component's encapsulation attribute. One
 * {@link effect} keeps it in step with the session.
 */
export class TerminalNoticeView {
  readonly element: HTMLDivElement;

  private readonly text: HTMLSpanElement;
  private readonly live: EffectRef;

  constructor(session: TerminalSession, injector: Injector) {
    this.element = document.createElement('div');
    this.element.className = 'mc-term-notice';
    // announced, because the pane it describes has just started scrolling sideways under
    // someone who may not be looking at this corner of it
    this.element.setAttribute('role', 'status');

    this.text = document.createElement('span');
    this.text.className = 'msg';

    const refit = document.createElement('button');
    refit.className = 'refit';
    refit.textContent = 'refit';
    // says outright that it clears: the floor only lifts on an empty buffer, so there is no
    // version of this button that keeps the scrollback
    refit.title = 'clear this pane so it fits its box again';
    refit.addEventListener('click', () => session.refit());

    this.element.append(this.text, refit);

    this.live = effect(() => {
      const over = session.overWide();
      this.element.classList.toggle('on', !!over);
      if (over) {
        this.text.textContent = `${over.cols} cols in a ${over.boxCols}-col pane — scroll, or`;
      }
    }, { injector });
  }

  dispose(): void {
    this.live.destroy();
  }
}
