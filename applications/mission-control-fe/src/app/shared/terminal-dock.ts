import type {
  DockviewApi, DockviewIDisposable, DockviewTheme, GroupPanelPartInitParameters, IContentRenderer,
  SerializedDockview,
} from 'dockview-core';
// The one value import of the library, and deliberately of the styled bundle:
// it is the only published entry that brings dockview's stylesheet with it.
// See src/dockview-core-styled.d.ts.
import { createDockview, themeDark } from 'dockview-core/dist/dockview-core.js';
import type { ITabRenderer } from 'dockview-core';
import { TerminalSession } from './terminal-session';

/** The one component name the dock registers — every panel is a terminal. */
const PANE = 'mc-terminal';
const TAB = 'mc-terminal-tab';

/**
 * The terminal chrome, as a dockview theme.
 *
 * <p>Built on dockview's own dark theme rather than declared from nothing: that
 * theme is a block of some forty CSS variables in dockview's stylesheet, and a
 * from-scratch class would have to redefine every one of them — including the
 * ones a future version adds. Keeping its class name means those all still
 * apply, and the handful that carry the panel's look are overridden against the
 * `--term-*` tokens in styles.scss, where a rule can actually reach DOM dockview
 * built itself.
 *
 * <p>What is set here is the behaviour a stylesheet cannot express: a visible gap
 * between groups, and a drop overlay covering the whole group rather than just
 * its content, so dragging a shell onto a tab strip reads as landing there.
 */
export const TERM_THEME: DockviewTheme = {
  ...themeDark,
  name: 'mission-control-terminal',
  gap: 2,
  dndPanelOverlay: 'group',
  dndTabIndicator: 'line',
};

/**
 * Where a new pane goes relative to the one it was split from.
 *
 * <p>Not a cosmetic choice. `right` halves the columns; `below` keeps every column
 * and halves the rows instead. A pane that has already printed will not be
 * narrowed either way — TerminalSession holds its grid at the width it drew at and
 * scrolls sideways rather than rewrapping — so what this really picks is whether
 * new output gets columns or rows. Which trade is right depends on what is being
 * read, so it is the operator's to make rather than ours to hard-code.
 */
export type SplitDirection = 'right' | 'below';

/**
 * What the dock needs the panel to decide, and what it reports back to it.
 *
 * <p>{@link createTab} is why this is an interface rather than a set of options: dockview owns
 * the tab strip, but what a tab *looks* like is the panel's business, and building it there is
 * what keeps this file free of any framework. A second tab presentation — narrower, read-only,
 * whatever — is a change in the panel and nothing here.
 */
export interface DockHooks {
  /** the tab chrome for a pane; the dock only ever hands it to dockview */
  createTab(session: TerminalSession): ITabRenderer;
  /** a pane went away — by its ×, or with the group it was the last member of */
  removed(id: string): void;
  /** the pane the toolbar should act on; null when the last one closed */
  focused(id: string | null): void;
  /** the arrangement changed and is worth writing down */
  arranged(): void;
}

/**
 * The split layout behind the terminal panel: a dockview grid whose every panel
 * is one {@link TerminalSession}.
 *
 * <p>Panels are rendered `always` rather than only while visible, which is the
 * whole reason this works: dockview keeps a hidden pane's element in the DOM
 * instead of tearing it out, so a background shell stays attached, keeps its
 * scrollback, and goes on streaming into its buffer. A session's host div is
 * created once and lives for the session's life — the pane element it sits in
 * is just where the dock currently puts it.
 *
 * <p>Floating and popped-out groups are off: xterm binds a terminal to the
 * document it was opened in, so a shell dragged into a second window would come
 * up unmeasurable and unstyled. Splitting and re-arranging inside the panel is
 * the whole offer.
 *
 * <p>Panel ids are session ids, which is what lets a saved layout be matched
 * back up with restored sessions by nothing more than a lookup.
 */
export class TerminalDock {
  private readonly api: DockviewApi;
  private readonly sessions = new Map<string, TerminalSession>();
  private readonly subs: DockviewIDisposable[] = [];
  /** set while a layout is being loaded, so the restore does not save itself back */
  private restoring = false;

  constructor(root: HTMLElement, private readonly hooks: DockHooks) {
    this.api = createDockview(root, {
      theme: TERM_THEME,
      defaultRenderer: 'always',
      disableFloatingGroups: true,
      singleTabMode: 'default',
      noPanelsOverlay: 'emptyGroup',
      createComponent: options => this.createPane(options.id),
      createTabComponent: options => this.createTab(options.id),
    });

    this.subs.push(
      this.api.onDidActivePanelChange(event => this.hooks.focused(event.panel?.id ?? null)),
      this.api.onDidRemovePanel(panel => {
        this.sessions.delete(panel.id);
        this.hooks.removed(panel.id);
      }),
      this.api.onDidLayoutChange(() => {
        if (!this.restoring) this.hooks.arranged();
      }),
    );
  }

  /** The sessions the dock is currently showing, in tab order. */
  panes(): TerminalSession[] {
    return this.api.panels
      .map(panel => this.sessions.get(panel.id))
      .filter((session): session is TerminalSession => !!session);
  }

  /**
   * Puts `sessions` back into `layout`, falling back to one group holding them
   * all when there is no layout to honour or it will not load.
   *
   * <p>A layout is data from localStorage, so a hand-edited or half-migrated one
   * has to be survivable: the fallback is not a nicety, it is the difference
   * between a bad payload costing the arrangement and it costing the panel.
   */
  restore(
    sessions: readonly TerminalSession[], layout: SerializedDockview | null,
    activeId: string | null,
  ): void {
    for (const session of sessions) this.sessions.set(session.id, session);

    if (layout) {
      this.restoring = true;
      try {
        this.api.fromJSON(layout);
      } catch {
        this.api.clear();   // half-applied is worse than none
      } finally {
        this.restoring = false;
      }
    }

    // whatever the layout did not place: every session when there was no layout,
    // and otherwise a shell opened while the panel was closed, or one from a
    // payload that predates arrangements being saved at all
    for (const session of sessions) {
      if (!this.api.getPanel(session.id)) this.addPanel(session, null);
    }
    if (activeId) this.focus(activeId);
  }

  /**
   * Adds a pane for `session`. `splitFrom` names the pane to split away from —
   * `direction` says which side of it to land on. Without `splitFrom` the pane
   * joins the focused pane's group as another tab.
   */
  add(
    session: TerminalSession, splitFrom?: string | null,
    direction: SplitDirection = 'right',
  ): void {
    this.sessions.set(session.id, session);
    this.addPanel(session, splitFrom ?? null, direction);
  }

  /** Bring a pane to the front of its group and give it the keyboard. */
  focus(id: string): void {
    const panel = this.api.getPanel(id);
    if (!panel) return;
    panel.api.setActive();
    this.sessions.get(id)?.focus();
  }

  /** Close a pane. Its session is disposed by the panel, via {@link DockHooks.removed}. */
  close(id: string): void {
    this.api.getPanel(id)?.api.close();
  }

  /** The arrangement, for persisting. */
  toJSON(): SerializedDockview | null {
    try {
      return this.api.toJSON();
    } catch {
      return null;   // never let a serialization fault take the panel down with it
    }
  }

  /**
   * Tell dockview how big it is. Its own observer does this in a browser; the
   * explicit call is for the moments an observer has not fired yet — the panel
   * has just opened, or its height was stepped by a button.
   */
  resize(width: number, height: number): void {
    if (width > 0 && height > 0) this.api.layout(width, height);
  }

  /** Refit every pane that is on screen — after a resize the panel itself drove. */
  fitAll(): void {
    for (const session of this.panes()) session.fitNow();
  }

  /** Hold all fits for the duration of a drag; see TerminalSession.setFitsSuspended. */
  suspendFits(suspended: boolean): void {
    for (const session of this.panes()) session.setFitsSuspended(suspended);
  }

  dispose(): void {
    for (const sub of this.subs) sub.dispose();
    this.subs.length = 0;
    this.sessions.clear();
    this.api.dispose();
  }

  private addPanel(
    session: TerminalSession, splitFrom: string | null,
    direction: SplitDirection = 'right',
  ): void {
    const beside = splitFrom && this.api.getPanel(splitFrom) ? splitFrom : null;
    this.api.addPanel({
      id: session.id,
      component: PANE,
      tabComponent: TAB,
      title: session.target().label,
      ...(beside ? { position: { referencePanel: beside, direction } } : {}),
    });
  }

  private createPane(id: string): IContentRenderer {
    const session = this.sessions.get(id);
    // A layout naming a panel with no session is pruned out before it ever gets
    // here (see pruneLayout), so this is a bug rather than a payload problem —
    // an empty pane says so instead of crashing the whole dock.
    return session ? new TerminalPaneView(session) : new MissingPaneView(id);
  }

  private createTab(id: string): ITabRenderer | undefined {
    const session = this.sessions.get(id);
    return session ? this.hooks.createTab(session) : undefined;
  }
}

/**
 * One pane: the box a session's host div is parked in.
 *
 * <p>All the layout a session ever hears about arrives through here. Visibility
 * gates its fits, a dimension change asks for one, and being made the active
 * panel hands it the keyboard. Nothing is pushed from the panel above.
 */
class TerminalPaneView implements IContentRenderer {
  readonly element: HTMLDivElement;

  private readonly subs: DockviewIDisposable[] = [];

  constructor(private readonly session: TerminalSession) {
    // styled here, not in the panel's stylesheet: created outside any template,
    // so emulated view encapsulation would never scope a class rule to it
    this.element = document.createElement('div');
    this.element.className = 'mc-term-pane';
    this.element.style.position = 'relative';
    this.element.style.width = '100%';
    this.element.style.height = '100%';
    this.element.style.overflow = 'hidden';
    this.element.appendChild(session.hostEl);
  }

  init(params: GroupPanelPartInitParameters): void {
    // safe while detached: xterm only logs about it, and every fit is guarded
    // against a degenerate measurement, so the first real one does the work
    this.session.ensureTerm();
    this.session.connectOnce();
    this.session.setVisible(params.api.isVisible);

    this.subs.push(
      params.api.onDidVisibilityChange(event => this.session.setVisible(event.isVisible)),
      params.api.onDidDimensionsChange(() => this.session.fitLater()),
      params.api.onDidActiveChange(event => {
        if (event.isActive) this.session.focus();
      }),
    );

    // clicking into a shell is how an operator picks which pane the toolbar acts
    // on; without this the tab strip would be the only way to say so
    this.element.addEventListener('focusin', () => params.api.setActive());
  }

  dispose(): void {
    for (const sub of this.subs) sub.dispose();
    this.subs.length = 0;
    // the session itself outlives the pane only until the panel disposes it —
    // the dock reports the removal and the panel owns that decision
  }
}

/** Placeholder for a panel id with no session behind it. See createPane(). */
class MissingPaneView implements IContentRenderer {
  readonly element: HTMLDivElement;

  constructor(id: string) {
    this.element = document.createElement('div');
    this.element.className = 'mc-term-pane';
    this.element.textContent = `no shell for ${id}`;
  }

  init(): void { /* nothing to wire — there is no session */ }
}
