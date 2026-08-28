import type { SerializedDockview } from 'dockview-core';
import { PersistedTab } from './terminal-session';

// What survives a reload of the terminal panel: which shells were open, how they
// were arranged, and which one had focus. Never the live socket or the
// scrollback — a restored tab reconnects as a brand-new exec.

const TABS_KEY = 'mc-terminal-tabs';

/**
 * Versioned envelope, so an older shape is discarded rather than misread. v1 is v2 without
 * `layout`, which is exactly what it meant: one group, every tab in it.
 */
interface PersistedTabs {
  v: 1 | 2;
  tabs: PersistedTab[];
  activeId: string | null;
  /** the dock layout: which groups exist, their order and their sizes (v2) */
  layout?: SerializedDockview | null;
}

/** The restored tabs and the arrangement to put them back into. */
export interface RestoredTabs {
  tabs: PersistedTab[];
  activeId: string | null;
  /** null means "no usable arrangement" — the caller stacks the tabs in one group. */
  layout: SerializedDockview | null;
}

/**
 * Reads the saved tabs. Only configured ones come back: an unconfigured
 * "(choose)" tab has no container to reconnect to, so restoring it would just
 * reopen an empty picker. Anything unreadable — private mode, a hand-edited
 * value, a shape from an older version — restores nothing rather than throwing.
 */
export function readTerminalTabs(): RestoredTabs {
  const empty: RestoredTabs = { tabs: [], activeId: null, layout: null };
  let raw: string | null;
  try {
    raw = localStorage.getItem(TABS_KEY);
  } catch {
    return empty;   // private mode
  }
  if (!raw) return empty;
  let data: PersistedTabs;
  try {
    data = JSON.parse(raw);
  } catch {
    return empty;
  }
  if (data?.v !== 1 && data?.v !== 2) return empty;
  if (!Array.isArray(data.tabs)) return empty;
  const tabs = data.tabs.filter(tab => tab && tab.containerId);
  if (!tabs.length) return empty;
  // the saved active tab may itself have been an unconfigured one
  const activeId = tabs.some(tab => tab.id === data.activeId) ? data.activeId : tabs[0].id;
  // the layout can name panels that did not come back (an unconfigured tab was
  // never saved), so it is pruned to what actually exists before being handed on
  const layout = data.layout
    ? pruneLayout(data.layout, new Set(tabs.map(tab => tab.id)))
    : null;
  return { tabs, activeId, layout };
}

/** Saves the tab targets and their arrangement. Unconfigured tabs are dropped
 *  here too, so what is written is exactly what {@link readTerminalTabs} would
 *  restore; the layout keeps their panels and {@link pruneLayout} takes them out
 *  on the way back in. */
export function writeTerminalTabs(
  tabs: readonly PersistedTab[], activeId: string | null,
  layout: SerializedDockview | null = null,
): void {
  const data: PersistedTabs = {
    v: 2, tabs: tabs.filter(tab => tab.containerId), activeId, layout,
  };
  try {
    localStorage.setItem(TABS_KEY, JSON.stringify(data));
  } catch { /* private mode */ }
}

/**
 * Drops everything from a saved layout that `keep` does not name, and returns
 * null when nothing usable is left.
 *
 * <p>The panels a layout references and the tabs that can be restored are two
 * separate lists — an unconfigured tab is deliberately not saved, and a
 * hand-edited payload can disagree in any direction. Handing dockview a layout
 * that names a panel we cannot build would leave it to invent one; pruning first
 * means a dropped shell costs its slot in the arrangement and nothing else.
 *
 * <p>Groups left with no panels are removed, and a branch left with a single
 * child collapses into that child, so the tree never degenerates into a split
 * with one side.
 */
export function pruneLayout(
  layout: SerializedDockview, keep: ReadonlySet<string>,
): SerializedDockview | null {
  const root = pruneNode(layout.grid?.root, keep);
  if (!root) return null;

  const panels: SerializedDockview['panels'] = {};
  for (const [id, panel] of Object.entries(layout.panels ?? {})) {
    if (keep.has(id)) panels[id] = panel;
  }
  if (!Object.keys(panels).length) return null;

  return {
    ...layout,
    grid: { ...layout.grid, root },
    panels,
    // never carried over: TerminalDock.restore() activates the focused pane itself, and that
    // activates its group. A saved value is overwritten a moment later at best, and names a
    // group pruning has just removed at worst.
    activeGroup: undefined,
    // a floating or popped-out terminal is not offered (xterm is bound to the
    // document it was opened in), so there is never anything here to carry over
    floatingGroups: undefined,
    popoutGroups: undefined,
  };
}

type Node = NonNullable<SerializedDockview['grid']>['root'];
type LeafData = { views?: string[]; activeView?: string };

/** Prunes one grid node, returning null when it holds nothing worth keeping. */
function pruneNode(node: Node | undefined, keep: ReadonlySet<string>): Node | null {
  if (!node) return null;

  if (node.type === 'branch') {
    const children = (Array.isArray(node.data) ? node.data : [])
      .map(child => pruneNode(child as Node, keep))
      .filter((child): child is Node => child !== null);
    if (!children.length) return null;
    // A split with one side is not a split, so a lone survivor is hoisted into this
    // branch's slot, carrying the branch's own size because that is the slot it now
    // fills. Only a leaf, though: the grid alternates orientation with depth, so
    // lifting a branch a level would leave its descendants' sizes measured along the
    // axis they no longer sit on. Such a branch keeps its wrapper instead.
    if (children.length === 1 && children[0].type === 'leaf') {
      return { ...children[0], size: node.size ?? children[0].size };
    }
    return { ...node, data: children };
  }

  const data = (node.data ?? {}) as LeafData;
  const views = (data.views ?? []).filter(id => keep.has(id));
  if (!views.length) return null;
  const activeView = data.activeView && views.includes(data.activeView)
    ? data.activeView : views[0];
  return { ...node, data: { ...data, views, activeView } as Node['data'] };
}
