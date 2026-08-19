import { PersistedTab } from './terminal-session';

// What survives a reload of the terminal panel: which shells were open and which
// one was on screen. Never the live socket or the scrollback — a restored tab
// reconnects as a brand-new exec.

const TABS_KEY = 'mc-terminal-tabs';

/** Versioned envelope, so an older shape is discarded rather than misread. */
interface PersistedTabs {
  v: 1;
  tabs: PersistedTab[];
  activeId: string | null;
}

/** The restored tab list. Empty when there was nothing usable to restore. */
export interface RestoredTabs {
  tabs: PersistedTab[];
  activeId: string | null;
}

/**
 * Reads the saved tabs. Only configured ones come back: an unconfigured
 * "(choose)" tab has no container to reconnect to, so restoring it would just
 * reopen an empty picker. Anything unreadable — private mode, a hand-edited
 * value, a shape from an older version — restores nothing rather than throwing.
 */
export function readTerminalTabs(): RestoredTabs {
  const empty: RestoredTabs = { tabs: [], activeId: null };
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
  if (data?.v !== 1 || !Array.isArray(data.tabs)) return empty;
  const tabs = data.tabs.filter(tab => tab && tab.containerId);
  if (!tabs.length) return empty;
  // the saved active tab may itself have been an unconfigured one
  const activeId = tabs.some(tab => tab.id === data.activeId) ? data.activeId : tabs[0].id;
  return { tabs, activeId };
}

/** Saves the tab targets. Unconfigured tabs are dropped here too, so what is
 *  written is exactly what {@link readTerminalTabs} would restore. */
export function writeTerminalTabs(tabs: readonly PersistedTab[], activeId: string | null): void {
  const data: PersistedTabs = {
    v: 1, tabs: tabs.filter(tab => tab.containerId), activeId,
  };
  try {
    localStorage.setItem(TABS_KEY, JSON.stringify(data));
  } catch { /* private mode */ }
}
