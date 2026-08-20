import { describe, expect, it } from 'vitest';
import { TerminalRequestStore } from './terminal-request-store';
import { agent, container } from '../../testing/models';

describe('TerminalRequestStore', () => {
  it('numbers every request, so an identical target still reads as a new one', () => {
    const store = new TerminalRequestStore();

    store.open();
    expect(store.request()).toEqual({ seq: 1 });

    store.open({ containerId: 'c-1' });
    store.open({ containerId: 'c-1' });
    expect(store.request()).toEqual({ containerId: 'c-1', seq: 3 });
  });

  it('starts with nothing to act on', () => {
    expect(new TerminalRequestStore().request()).toBeNull();
  });
});

describe('TerminalRequestStore agent shells', () => {
  const c = container('c-1', { hostId: 'dh-edge' });

  it('pins the tab to the profile and drops straight into its session', () => {
    const store = new TerminalRequestStore();

    store.openAgentShell(agent('atlas'), c);

    expect(store.request()).toEqual({
      seq: 1,
      hostId: 'dh-edge',
      containerId: 'c-1',
      // the profile's own name labels the tab, not the container it happens to run in
      label: 'atlas',
      // what lets a second click focus this tab instead of stacking another shell
      agentKey: 'atlas',
      command: 'hermes -p atlas',
    });
  });

  it('invokes the default profile bare, the way hermes takes it', () => {
    const store = new TerminalRequestStore();

    store.openAgentShell(agent('default'), c);

    expect(store.request()?.command).toBe('hermes');
  });

  it('opens a plain shell for a name it will not type blind', () => {
    const store = new TerminalRequestStore();

    store.openAgentShell(agent('a-1', { name: 'atlas; rm -rf /' }), c);

    // the tab is still the profile's — only the command is withheld
    expect(store.request()?.command).toBeUndefined();
    expect(store.request()?.agentKey).toBe('a-1');
  });
});
