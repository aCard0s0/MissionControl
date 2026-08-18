import { describe, expect, it } from 'vitest';
import { OLLAMA_PREFIX, ollamaOptionForBaseUrl } from './provider-resolve';

// A template stores ollama as a bare provider plus a baseUrl; the picker lists
// one option per registered instance. Getting this wrong prefills a provider
// whose model list comes from a different machine.
describe('ollamaOptionForBaseUrl', () => {
  const instances = [
    { name: 'workstation', url: 'http://10.0.0.5:11434' },
    { name: 'laptop', url: 'http://127.0.0.1:11434' },
  ];

  it('matches a stored baseUrl back to the instance that serves it', () => {
    expect(ollamaOptionForBaseUrl('http://127.0.0.1:11434', instances))
      .toBe(OLLAMA_PREFIX + 'laptop');
  });

  it('ignores an OpenAI-compatible /v1 suffix, which is how the url is persisted', () => {
    expect(ollamaOptionForBaseUrl('http://10.0.0.5:11434/v1', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(ollamaOptionForBaseUrl('http://10.0.0.5:11434/v1/', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
  });

  it('ignores trailing slashes on either side of the comparison', () => {
    expect(ollamaOptionForBaseUrl('http://10.0.0.5:11434///', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(ollamaOptionForBaseUrl('http://x:1', [{ name: 'x', url: 'http://x:1//' }]))
      .toBe(OLLAMA_PREFIX + 'x');
  });

  it('falls back to the first instance rather than leaving the picker unset', () => {
    // an unmatched url means the instance was renamed or removed; an empty
    // selection would let the form submit a provider with no endpoint
    expect(ollamaOptionForBaseUrl('http://gone:11434', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(ollamaOptionForBaseUrl('', instances)).toBe(OLLAMA_PREFIX + 'workstation');
  });

  it('returns null when no ollama instance is registered at all', () => {
    expect(ollamaOptionForBaseUrl('http://10.0.0.5:11434', [])).toBeNull();
    expect(ollamaOptionForBaseUrl('', [])).toBeNull();
  });

  it('does not treat a different port or host as the same instance', () => {
    const single = [{ name: 'a', url: 'http://10.0.0.5:11434' }];
    // falls back to 'a' either way, but the match must not be what selected it
    expect(ollamaOptionForBaseUrl('http://10.0.0.5:11435', single)).toBe(OLLAMA_PREFIX + 'a');
    expect(ollamaOptionForBaseUrl('http://10.0.0.6:11434', [
      { name: 'first', url: 'http://nope:1' }, { name: 'second', url: 'http://10.0.0.6:11434' },
    ])).toBe(OLLAMA_PREFIX + 'second');
  });
});
