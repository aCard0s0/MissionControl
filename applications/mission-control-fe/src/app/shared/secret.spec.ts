import { describe, expect, it } from 'vitest';
import { maskTail } from './secret';

describe('maskTail', () => {
  it('shows only the last four characters of a credential', () => {
    expect(maskTail('sk-ant-api03-9f2caF92')).toBe('…aF92');
  });

  it('renders empty for an absent value instead of an ellipsis with nothing after it', () => {
    expect(maskTail(null)).toBe('');
    expect(maskTail(undefined)).toBe('');
    expect(maskTail('')).toBe('');
  });

  it('does not pad a short value, so a 3-character secret is not disguised as longer', () => {
    expect(maskTail('abc')).toBe('…abc');
    expect(maskTail('abcd')).toBe('…abcd');
    expect(maskTail('abcde')).toBe('…bcde');
  });
});
