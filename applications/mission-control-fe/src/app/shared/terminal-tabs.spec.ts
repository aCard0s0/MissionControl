import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PersistedTab } from './terminal-session';
import { readTerminalTabs, writeTerminalTabs } from './terminal-tabs';

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
      tabs: [tab('t-1'), tab('t-2', { containerId: 'c-2' })], activeId: 't-2',
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
    expect(readTerminalTabs()).toEqual({ tabs: [], activeId: null });
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
    expect(readTerminalTabs()).toEqual({ tabs: [], activeId: null });
  });
});
