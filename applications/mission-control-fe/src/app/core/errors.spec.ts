import { describe, expect, it } from 'vitest';
import { errorMessage } from './errors';

describe('errorMessage', () => {
  it('reads the message off whatever the rejection actually carried', () => {
    expect(errorMessage(new Error('host still owns two containers')))
      .toBe('host still owns two containers');
    // a rejected value that is not an Error but names its own message
    expect(errorMessage({ message: 'socket hang up' })).toBe('socket hang up');
    expect(errorMessage('plain string')).toBe('plain string');
  });

  it('falls back when the failure carried no words of its own', () => {
    // an aborted request and a rejected fetch both reject with nothing to say
    expect(errorMessage(new Error(''), 'log refresh failed')).toBe('log refresh failed');
    expect(errorMessage(undefined, 'log refresh failed')).toBe('log refresh failed');
    expect(errorMessage(null, 'log refresh failed')).toBe('log refresh failed');
    expect(errorMessage({}, 'log refresh failed')).toBe('log refresh failed');
    expect(errorMessage('   ', 'log refresh failed')).toBe('log refresh failed');
  });

  it('says something rather than nothing when the caller names no fallback', () => {
    expect(errorMessage(new Error(''))).toBe('unknown error');
  });
});
