import type { SerializedDockview } from 'dockview-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PersistedTab } from './terminal-session';
import { pruneLayout, readTerminalTabs, writeTerminalTabs } from './terminal-tabs';

const KEY = 'mc-terminal-tabs';

const tab = (id: string, patch: Partial<PersistedTab> = {}): PersistedTab =>
  ({ id, hostId: 'dh-local', containerId: 'c-1', label: 'hermes-prod', ...patch });

describe('terminal tab persistence', () => {
  beforeEach(() => localStorage.clear());

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('restores the tabs and which one was on screen', () => {
    writeTerminalTabs([tab('t-1'), tab('t-2', { containerId: 'c-2' })], 't-2');

    expect(readTerminalTabs()).toEqual({
      tabs: [tab('t-1'), tab('t-2', { containerId: 'c-2' })], activeId: 't-2', layout: null,
    });
  });

  it('keeps the agent identity and startup command a shell tab carries', () => {
    writeTerminalTabs([tab('t-1', { agentKey: 'a-1', command: 'hermes -p atlas' })], 't-1');

    expect(readTerminalTabs().tabs[0]).toMatchObject({
      agentKey: 'a-1', command: 'hermes -p atlas',
    });
  });

  it('never saves an unconfigured tab, which has no shell to reconnect to', () => {
    writeTerminalTabs([tab('t-1'), tab('t-2', { containerId: '', label: '(choose)' })], 't-2');

    const restored = readTerminalTabs();
    expect(restored.tabs.map(t => t.id)).toEqual(['t-1']);
    // the saved active tab was the unconfigured one, so the survivor takes over
    expect(restored.activeId).toBe('t-1');
  });

  it('falls back to the first tab when the active one is no longer in the list', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, tabs: [tab('t-1')], activeId: 't-9' }));

    expect(readTerminalTabs().activeId).toBe('t-1');
  });

  it('restores nothing when there is nothing saved', () => {
    expect(readTerminalTabs()).toEqual({ tabs: [], activeId: null, layout: null });
  });

  it('restores nothing from an unreadable or foreign payload', () => {
    localStorage.setItem(KEY, 'not json');
    expect(readTerminalTabs().tabs).toEqual([]);

    localStorage.setItem(KEY, JSON.stringify({ v: 0, tabs: [tab('t-1')], activeId: 't-1' }));
    expect(readTerminalTabs().tabs).toEqual([]);

    localStorage.setItem(KEY, JSON.stringify({ v: 1, tabs: 'nope', activeId: null }));
    expect(readTerminalTabs().tabs).toEqual([]);

    localStorage.setItem(KEY, JSON.stringify({ v: 1, tabs: [null], activeId: null }));
    expect(readTerminalTabs().tabs).toEqual([]);
  });

  it('gives up quietly when storage is unavailable, rather than breaking the panel', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(() => writeTerminalTabs([tab('t-1')], 't-1')).not.toThrow();

    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readTerminalTabs()).toEqual({ tabs: [], activeId: null, layout: null });
  });
});

/**
 * The arrangement is dockview's own serialized grid: a tree of branches and leaf
 * groups, each group naming the panel ids it holds. Panel ids are session ids,
 * which is what lets a saved arrangement be matched back up with restored tabs.
 */
describe('terminal arrangement persistence', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  /** Two groups side by side, one tab in each. Shaped by hand rather than taken
   *  from a live dock, so what these tests pin is this module's own contract. */
  const split = (left: string, right: string) => ({
    grid: {
      orientation: 'HORIZONTAL',
      width: 800,
      height: 300,
      root: {
        type: 'branch',
        data: [
          { type: 'leaf', size: 400, data: { id: 'g-left', views: [left], activeView: left } },
          { type: 'leaf', size: 400, data: { id: 'g-right', views: [right], activeView: right } },
        ],
      },
    },
    panels: {
      [left]: { id: left, contentComponent: 'mc-terminal' },
      [right]: { id: right, contentComponent: 'mc-terminal' },
    },
    activeGroup: 'g-right',
  }) as unknown as SerializedDockview;

  it('brings the splits back, not just the tabs that were in them', () => {
    const layout = split('t-1', 't-2');
    writeTerminalTabs([tab('t-1'), tab('t-2', { containerId: 'c-2' })], 't-2', layout);

    expect(readTerminalTabs().layout).toEqual(layout);
  });

  it('reads a v1 payload as tabs with no arrangement — one group is what it meant', () => {
    localStorage.setItem(KEY, JSON.stringify({ v: 1, tabs: [tab('t-1')], activeId: 't-1' }));

    const restored = readTerminalTabs();
    expect(restored.tabs.map(t => t.id)).toEqual(['t-1']);
    expect(restored.layout).toBeNull();
  });

  it('drops the pane of a tab that was never saved, keeping the rest of the split', () => {
    // t-2 was unconfigured, so writeTerminalTabs did not save it; its slot in the
    // arrangement has to go too, and the split it was half of collapses
    writeTerminalTabs(
      [tab('t-1'), tab('t-2', { containerId: '', label: '(choose)' })],
      't-1', split('t-1', 't-2'));

    const layout = readTerminalTabs().layout!;
    expect(Object.keys(layout.panels)).toEqual(['t-1']);
    expect(layout.grid.root.type).toBe('leaf');
    expect(layout.grid.root.data).toMatchObject({ id: 'g-left', views: ['t-1'] });
  });

  it('restores no arrangement at all when nothing in it survives', () => {
    localStorage.setItem(KEY, JSON.stringify({
      v: 2, tabs: [tab('t-9')], activeId: 't-9', layout: split('t-1', 't-2'),
    }));

    expect(readTerminalTabs().layout).toBeNull();
  });
});

describe('pruneLayout', () => {
  const leaf = (id: string, views: string[], activeView?: string, size?: number) =>
    ({ type: 'leaf' as const, data: { id, views, activeView }, size });

  const grid = (root: unknown, panels: string[]) => ({
    grid: { orientation: 'HORIZONTAL', width: 800, height: 300, root },
    panels: Object.fromEntries(panels.map(id => [id, { id }])),
  }) as unknown as SerializedDockview;

  it('moves a group\'s active tab off a panel that is going away', () => {
    const pruned = pruneLayout(grid(leaf('g', ['a', 'b'], 'b'), ['a', 'b']), new Set(['a']));

    expect(pruned!.grid.root.data).toMatchObject({ views: ['a'], activeView: 'a' });
  });

  it('forgets an active group that pruning removed', () => {
    const layout = {
      ...grid({
        type: 'branch',
        data: [leaf('g-1', ['a']), leaf('g-2', ['b'])],
      }, ['a', 'b']),
      activeGroup: 'g-2',
    };

    expect(pruneLayout(layout, new Set(['a']))!.activeGroup).toBeUndefined();
  });

  it('hoists a lone surviving leaf into the branch\'s slot, taking its size', () => {
    const layout = grid({
      type: 'branch',
      data: [leaf('g-1', ['a']), leaf('g-2', ['b'])],
      size: 700,
    }, ['a', 'b']);

    const pruned = pruneLayout(layout, new Set(['a']))!;
    expect(pruned.grid.root.type).toBe('leaf');
    // the leaf now fills the slot the branch held, so it takes the branch's extent
    expect(pruned.grid.root.size).toBe(700);
  });

  it('keeps the wrapper around a lone surviving branch, which cannot change depth', () => {
    // The grid alternates orientation with depth: lifting this branch a level would
    // leave its leaves' sizes measured along the axis they no longer sit on, so the
    // restored split comes back with the wrong proportions.
    const nested = {
      type: 'branch' as const,
      data: [leaf('g-1', ['a']), leaf('g-2', ['b'])],
      size: 800,
    };
    const layout = grid({
      type: 'branch',
      data: [nested, leaf('g-3', ['c'])],
    }, ['a', 'b', 'c']);

    const pruned = pruneLayout(layout, new Set(['a', 'b']))!;
    expect(pruned.grid.root.type).toBe('branch');
    const kids = pruned.grid.root.data as { type: string }[];
    expect(kids.map(k => k.type)).toEqual(['branch']);
  });

  it('never leaves a floating or popped-out group behind, which is not offered here', () => {
    const layout = {
      ...grid(leaf('g', ['a']), ['a']),
      floatingGroups: [{ data: { id: 'f', views: ['a'] }, position: {} }],
      popoutGroups: [{ data: { id: 'p', views: ['a'] }, position: null }],
    } as unknown as SerializedDockview;

    const pruned = pruneLayout(layout, new Set(['a']))!;
    expect(pruned.floatingGroups).toBeUndefined();
    expect(pruned.popoutGroups).toBeUndefined();
  });
});
