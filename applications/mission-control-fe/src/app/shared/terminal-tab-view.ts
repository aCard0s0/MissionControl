import { EffectRef, Injector, effect } from '@angular/core';
import type { DockviewIDisposable, ITabRenderer, TabPartInitParameters } from 'dockview-core';
import { statusTone } from './status-dot';
import { TerminalSession } from './terminal-session';

/** What a tab needs to know that only the panel can answer. */
export interface TabInfo {
  /** the container's live name (so a rename shows) and whether it is still in the inventory */
  state(session: TerminalSession): { label: string; stale: boolean };
  /** open the container picker for this tab, anchored on the caret it was clicked from */
  pick(session: TerminalSession, anchor: HTMLElement): void;
}

/**
 * A terminal tab, as plain DOM.
 *
 * <p>dockview renders the tab strip, so the tab itself cannot be an Angular
 * template — nothing in it would carry the component's encapsulation attribute,
 * and there is no host component to bind to. It is built by hand instead and
 * kept current by one {@link effect}: label, status tone and the struck-through
 * "gone" state all read signals, so a rename or a dropped socket lands here
 * without the panel pushing anything.
 *
 * The class names are the ones the panel's stylesheet and its specs already use
 * (`.tab`, `.lbl`, `.caret`, `.x`) — dockview supplies the strip, not the look.
 */
export class TerminalTabView implements ITabRenderer {
  readonly element: HTMLDivElement;

  private readonly dot: HTMLSpanElement;
  private readonly lbl: HTMLSpanElement;
  private readonly caret: HTMLButtonElement;
  private live: EffectRef | null = null;

  constructor(
    private readonly session: TerminalSession,
    private readonly info: TabInfo,
    private readonly injector: Injector,
  ) {
    this.element = document.createElement('div');
    this.element.className = 'tab';
    // dockview's strip is divs, so the tab has to declare what it is. Without this a pane is
    // reachable only by pointer: nothing here is a control as far as the keyboard is concerned.
    this.element.setAttribute('role', 'tab');
    // roving tabindex — one stop for the whole strip, then Ctrl+Shift+←/→ between panes,
    // rather than a Tab press per open shell before anything else on the page
    this.element.tabIndex = -1;
    // which shell this tab is for, so the panel can find a tab it did not just
    // get handed — anchoring the container picker on a freshly made one
    this.element.dataset['session'] = session.id;

    this.dot = document.createElement('span');
    this.dot.className = 'dot';

    this.lbl = document.createElement('span');
    this.lbl.className = 'lbl';

    this.caret = button('caret', '▾', 'change container');
    const close = button('x', '×', 'close');

    this.element.append(this.dot, this.lbl, this.caret, close);

    // the caret and the × are actions on this tab, not ways of selecting it —
    // letting either bubble would also activate the tab (or start a tab drag)
    this.caret.addEventListener('click', event => {
      event.stopPropagation();
      this.info.pick(this.session, this.caret);
    });
    close.addEventListener('click', event => {
      event.stopPropagation();
      this.api?.close();
    });
  }

  private api: TabPartInitParameters['api'] | null = null;
  private readonly subs: DockviewIDisposable[] = [];

  init(params: TabPartInitParameters): void {
    this.api = params.api;
    // dockview activates a tab from a pointer press on the strip; binding click
    // as well is what makes a plain programmatic click select it, which is both
    // how the specs drive the panel and what a keyboard-issued click does
    this.element.addEventListener('click', this.onClick);

    // Two states, not one. `isActive` is the focused pane — the group has the
    // keyboard AND this is its visible tab — while `isVisible` is merely on
    // screen. With groups side by side those stopped being the same question,
    // and a tab showing in an unfocused group has to look like neither the
    // focused one nor a tab buried in a stack.
    this.releaseSubs();
    this.setActive(params.api.isActive);
    this.element.classList.toggle('vis', params.api.isVisible);
    this.subs.push(
      params.api.onDidActiveChange(event => this.setActive(event.isActive)),
      params.api.onDidVisibilityChange(event =>
        this.element.classList.toggle('vis', event.isVisible)),
    );

    // Enter/Space on a focused tab, which is what a role=tab is expected to answer to. The
    // chord that moves between panes is the dock's — it listens in capture, above this.
    this.element.addEventListener('keydown', this.onKeydown);

    this.live?.destroy();
    this.live = effect(() => {
      const { label, stale } = this.info.state(this.session);
      this.lbl.textContent = label;
      this.lbl.classList.toggle('gone', stale);
      this.dot.className = `dot ${statusTone(this.session.status())}`;
      // the title is the whole story a truncated tab cannot tell
      this.element.title = `${label} — ${this.session.status()}`;
    }, { injector: this.injector });
  }

  dispose(): void {
    this.live?.destroy();
    this.live = null;
    this.releaseSubs();
    this.element.removeEventListener('click', this.onClick);
    this.element.removeEventListener('keydown', this.onKeydown);
  }

  /** Active is both a look and a promise to assistive tech, and the only tab in the tab order. */
  private setActive(active: boolean): void {
    this.element.classList.toggle('act', active);
    this.element.setAttribute('aria-selected', String(active));
    this.element.tabIndex = active ? 0 : -1;
  }

  private releaseSubs(): void {
    for (const sub of this.subs) sub.dispose();
    this.subs.length = 0;
  }

  /** Bound once so dispose() can take it off again. */
  private readonly onClick = (): void => {
    this.api?.setActive();
  };

  private readonly onKeydown = (event: KeyboardEvent): void => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();   // Space would otherwise scroll the panel
    this.api?.setActive();
    this.session.focus();
  };
}

const button = (className: string, text: string, title: string): HTMLButtonElement => {
  const el = document.createElement('button');
  el.className = className;
  el.textContent = text;
  el.title = title;
  return el;
};
