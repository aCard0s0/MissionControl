import { describe, expect, it } from 'vitest';
import { LlmProvider, InferenceEndpoint, ProfileTemplate } from '../core/models';
import {
  OLLAMA_PREFIX, endpointBaseUrl, endpointOptionForBaseUrl, providerNameOf, providerOptionFor,
  providerOptions, resolveProviderOption, templateProvidesKey,
} from './provider-resolve';

// A template stores ollama as a bare provider plus a baseUrl; the picker lists
// one option per registered instance. Getting this wrong prefills a provider
// whose model list comes from a different machine.
describe('endpointOptionForBaseUrl', () => {
  const instances = [
    { name: 'workstation', url: 'http://10.0.0.5:11434' },
    { name: 'laptop', url: 'http://127.0.0.1:11434' },
  ];

  it('matches a stored baseUrl back to the instance that serves it', () => {
    expect(endpointOptionForBaseUrl('http://127.0.0.1:11434', instances))
      .toBe(OLLAMA_PREFIX + 'laptop');
  });

  it('ignores an OpenAI-compatible /v1 suffix, which is how the url is persisted', () => {
    expect(endpointOptionForBaseUrl('http://10.0.0.5:11434/v1', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(endpointOptionForBaseUrl('http://10.0.0.5:11434/v1/', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
  });

  it('ignores trailing slashes on either side of the comparison', () => {
    expect(endpointOptionForBaseUrl('http://10.0.0.5:11434///', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(endpointOptionForBaseUrl('http://x:1', [{ name: 'x', url: 'http://x:1//' }]))
      .toBe(OLLAMA_PREFIX + 'x');
  });

  it('falls back to the first instance rather than leaving the picker unset', () => {
    // an unmatched url means the instance was renamed or removed; an empty
    // selection would let the form submit a provider with no endpoint
    expect(endpointOptionForBaseUrl('http://gone:11434', instances))
      .toBe(OLLAMA_PREFIX + 'workstation');
    expect(endpointOptionForBaseUrl('', instances)).toBe(OLLAMA_PREFIX + 'workstation');
  });

  it('returns null when no ollama instance is registered at all', () => {
    expect(endpointOptionForBaseUrl('http://10.0.0.5:11434', [])).toBeNull();
    expect(endpointOptionForBaseUrl('', [])).toBeNull();
  });

  it('does not treat a different port or host as the same instance', () => {
    const single = [{ name: 'a', url: 'http://10.0.0.5:11434' }];
    // falls back to 'a' either way, but the match must not be what selected it
    expect(endpointOptionForBaseUrl('http://10.0.0.5:11435', single)).toBe(OLLAMA_PREFIX + 'a');
    expect(endpointOptionForBaseUrl('http://10.0.0.6:11434', [
      { name: 'first', url: 'http://nope:1' }, { name: 'second', url: 'http://10.0.0.6:11434' },
    ])).toBe(OLLAMA_PREFIX + 'second');
  });
});

const llm: LlmProvider[] = [
  { key: 'nous', label: 'Nous Portal', needsKey: false, oauth: true, hasCatalog: true, envVar: null },
  { key: 'anthropic', label: 'Anthropic', needsKey: true, oauth: false, hasCatalog: true,
    envVar: 'ANTHROPIC_API_KEY' },
];

const instance = (name: string, url: string): InferenceEndpoint => ({
  id: `mp-${name}`, name, url, kind: 'ollama', status: 'connected', version: null, detail: null,
  canManageModels: true,
});

const ollama = [instance('workstation', 'http://10.0.0.5:11434')];

describe('providerOptions', () => {
  it('lists the registry by label, then one entry per ollama instance', () => {
    expect(providerOptions(llm, ollama)).toEqual([
      { value: 'nous', label: 'Nous Portal' },
      { value: 'anthropic', label: 'Anthropic' },
      { value: 'ollama: workstation', label: 'Ollama: workstation' },
    ]);
  });

  it('offers nothing rather than a bare ollama entry when none is registered', () => {
    expect(providerOptions([], [])).toEqual([]);
    expect(providerOptions(llm, []).map(o => o.value)).toEqual(['nous', 'anthropic']);
  });
});

describe('resolveProviderOption', () => {
  it('passes a registry provider through with no endpoint of its own', () => {
    expect(resolveProviderOption('anthropic', ollama)).toEqual({ provider: 'anthropic' });
  });

  it('flattens an ollama option into the bare provider plus its /v1 endpoint', () => {
    expect(resolveProviderOption('ollama: workstation', ollama))
      .toEqual({ provider: 'ollama', baseUrl: 'http://10.0.0.5:11434/v1' });
    expect(endpointBaseUrl({ url: 'http://10.0.0.5:11434///' })).toBe('http://10.0.0.5:11434/v1');
  });

  it('refuses an ollama instance that is no longer registered', () => {
    // guessing another instance would point the profile at a machine the
    // operator never chose, so the caller has to abort instead
    expect(resolveProviderOption('ollama: gone', ollama)).toBeNull();
    expect(resolveProviderOption('ollama: workstation', [])).toBeNull();
  });
});

describe('providerNameOf', () => {
  it('collapses every ollama option to the bare provider a template stores', () => {
    expect(providerNameOf('ollama: workstation')).toBe('ollama');
    // still ollama when the instance has gone — the endpoint may be typed by hand
    expect(providerNameOf('ollama: gone')).toBe('ollama');
    expect(providerNameOf('anthropic')).toBe('anthropic');
  });
});

describe('providerOptionFor', () => {
  const options = providerOptions(llm, ollama);

  it('selects a stored provider the picker still offers', () => {
    expect(providerOptionFor('anthropic', '', options, ollama)).toBe('anthropic');
  });

  it('resolves a stored bare ollama back to the instance serving that endpoint', () => {
    expect(providerOptionFor('ollama', 'http://10.0.0.5:11434/v1', options, ollama))
      .toBe('ollama: workstation');
  });

  it('answers null when nothing matches, leaving the fallback to the caller', () => {
    expect(providerOptionFor('retired-vendor', '', options, ollama)).toBeNull();
    expect(providerOptionFor('ollama', 'http://gone:11434', options, [])).toBeNull();
    expect(providerOptionFor('', '', options, ollama)).toBeNull();
  });
});

describe('templateProvidesKey', () => {
  const template = (secrets: ProfileTemplate['secrets']) => ({ secrets });
  const anthropic = llm[1];

  it('accepts a stored key that still decrypts', () => {
    expect(templateProvidesKey(
      template([{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: true }]), anthropic)).toBe(true);
  });

  it('refuses the placeholders a captured template records', () => {
    expect(templateProvidesKey(
      template([{ key: 'ANTHROPIC_API_KEY', set: false, recoverable: false }]), anthropic)).toBe(false);
    expect(templateProvidesKey(
      template([{ key: 'ANTHROPIC_API_KEY', set: true, recoverable: false }]), anthropic)).toBe(false);
  });

  it('refuses a key stored under another provider\'s variable', () => {
    expect(templateProvidesKey(
      template([{ key: 'OPENAI_API_KEY', set: true, recoverable: true }]), anthropic)).toBe(false);
  });

  it('answers false for a keyless provider or no template at all', () => {
    expect(templateProvidesKey(template([]), llm[0])).toBe(false);
    expect(templateProvidesKey(null, anthropic)).toBe(false);
    expect(templateProvidesKey(template([]), null)).toBe(false);
  });
});
