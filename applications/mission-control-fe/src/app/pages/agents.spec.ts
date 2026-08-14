import '@angular/compiler';
import { describe, expect, it } from 'vitest';
import { agentSessionCommand } from './agents';

describe('Agent shell shortcut command', () => {
  it('scopes a named profile with -p', () => {
    expect(agentSessionCommand('ops-bot')).toBe('hermes -p ops-bot');
  });

  it('invokes the default profile bare — hermes takes -p only for named ones', () => {
    expect(agentSessionCommand('default')).toBe('hermes');
  });

  it('accepts the punctuation hermes allows in a profile directory name', () => {
    expect(agentSessionCommand('ops.bot_2-v1')).toBe('hermes -p ops.bot_2-v1');
  });

  it('refuses a name carrying shell metacharacters so nothing is typed blind', () => {
    for (const name of ['ops; rm -rf /', 'ops bot', 'ops$(id)', 'ops`id`', 'ops&&id', '../escape', '']) {
      expect(agentSessionCommand(name)).toBeUndefined();
    }
  });
});
