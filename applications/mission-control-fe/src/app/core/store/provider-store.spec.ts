import { describe, expect, it, vi } from 'vitest';
import { InferenceEndpoint } from '../models';
import { FALLBACK_MODELS } from './provider-defaults';
import { flush, liveError, testSlices } from '../../testing/store';

const provider = (id: string, patch: Partial<InferenceEndpoint> = {}): InferenceEndpoint => ({
  id, name: id, url: `http://${id}:11434`, kind: 'ollama', status: 'connected',
  version: '0.6.4', detail: null, canManageModels: true, ...patch,
});

const store = (providers: Record<string, unknown>) => {
  const { ctx, providers: store } = testSlices({ providers });
  return { ctx, store };
};

describe('ProviderStore LLM registry', () => {
  it('starts empty — the backend registry is the only one', () => {
    expect(store({}).store.llmProviders()).toEqual([]);
  });

  it('fills the picker from the backend registry', async () => {
    const registry = [{ key: 'nous', label: 'Nous', needsKey: true, oauth: false, hasCatalog: true, envVar: 'NOUS_API_KEY' }];
    const built = store({ registry: vi.fn().mockResolvedValue(registry) });

    await built.store.refreshRegistry();

    expect(built.store.llmProviders()).toEqual(registry);
  });

  it('resolves rather than throwing when the registry is unreachable, so the rest of the initial load survives', async () => {
    const failing = store({ registry: vi.fn().mockRejectedValue(new Error('offline')) });

    await expect(failing.store.refreshRegistry()).resolves.toBeUndefined();

    expect(failing.store.llmProviders()).toEqual([]);
  });
});

describe('ProviderStore ollama endpoints', () => {
  it('keeps the last inventory when a poll fails, rather than blanking it', async () => {
    const list = vi.fn().mockResolvedValueOnce([provider('mp-1')]).mockRejectedValue(new Error('down'));
    const built = store({ list });
    await built.store.refresh();

    await built.store.refresh();

    expect(built.store.endpoints().map(p => p.id)).toEqual(['mp-1']);
    expect(liveError(built.ctx)).toBeNull();
  });

  it('re-reads the list after adding one, since the backend assigns the id', async () => {
    const add = vi.fn().mockResolvedValue(provider('mp-new'));
    const list = vi.fn().mockResolvedValue([provider('mp-new')]);
    const built = store({ add, list });

    built.store.add('lab', 'http://ollama:11434');
    await flush();

    expect(add).toHaveBeenCalledWith('lab', 'http://ollama:11434');
    expect(built.store.endpoints().map(p => p.id)).toEqual(['mp-new']);
  });

  it('reports an add the backend refused', async () => {
    const built = store({ add: vi.fn().mockRejectedValue(new Error('duplicate url')) });

    built.store.add('lab', 'http://ollama:11434');
    await flush();

    expect(liveError(built.ctx)).toBe('add provider failed: duplicate url');
  });

  it('re-reads the list after removing one, and reports a refused remove', async () => {
    const list = vi.fn().mockResolvedValue([]);
    const ok = store({ remove: vi.fn().mockResolvedValue(undefined), list });
    const bad = store({ remove: vi.fn().mockRejectedValue(new Error('in use')) });

    ok.store.remove('mp-1');
    bad.store.remove('mp-1');
    await flush();

    expect(list).toHaveBeenCalled();
    expect(liveError(bad.ctx)).toBe('remove provider failed: in use');
  });

  it('blanks the status while a check runs, then shows what came back', async () => {
    let land!: (value: InferenceEndpoint) => void;
    const check = vi.fn().mockReturnValue(new Promise<InferenceEndpoint>(r => { land = r; }));
    const built = store({ list: vi.fn().mockResolvedValue([provider('mp-1')]), check });
    await built.store.refresh();

    built.store.check('mp-1');
    expect(built.store.endpoints()[0].status).toBe('unknown');

    land(provider('mp-1', { status: 'error', detail: 'connection refused' }));
    await flush();
    expect(built.store.endpoints()[0].status).toBe('error');
  });

  it('falls back to a full re-read when a check itself fails', async () => {
    const list = vi.fn().mockResolvedValue([provider('mp-1')]);
    const built = store({ list, check: vi.fn().mockRejectedValue(new Error('timeout')) });
    await built.store.refresh();

    built.store.check('mp-1');
    await flush();

    expect(liveError(built.ctx)).toBe('provider check failed: timeout');
    expect(list).toHaveBeenCalledTimes(2);
  });
});

describe('ProviderStore models', () => {
  it('answers an empty list and says why a model listing failed', async () => {
    const built = store({ models: vi.fn().mockRejectedValue(new Error('no such provider')) });

    expect(await built.store.models('mp-1')).toEqual([]);
    expect(liveError(built.ctx)).toBe('model list failed: no such provider');
  });

  it('reports a failed pull and a failed delete by name', async () => {
    const pull = store({ pullModel: vi.fn().mockRejectedValue(new Error('disk full')) });
    const del = store({ deleteModel: vi.fn().mockRejectedValue(new Error('model in use')) });

    await pull.store.pullModel('mp-1', 'llama3');
    await del.store.deleteModel('mp-1', 'llama3');

    expect(liveError(pull.ctx)).toBe('pull failed: disk full');
    expect(liveError(del.ctx)).toBe('model delete failed: model in use');
  });

  it('treats pull progress as best-effort — a failed poll is not an error', async () => {
    const built = store({ pullStatus: vi.fn().mockRejectedValue(new Error('gone')) });

    expect(await built.store.pullStatus('mp-1')).toEqual([]);
    expect(liveError(built.ctx)).toBeNull();
  });

  it('serves the configured catalog for a provider key', async () => {
    const built = store({ modelCatalog: vi.fn().mockResolvedValue({ models: ['m-1', 'm-2'] }) });

    expect(await built.store.modelCatalog('anthropic')).toEqual(['m-1', 'm-2']);
  });

  it('falls back to the bundled list when the catalog cannot be read', async () => {
    const [key] = Object.keys(FALLBACK_MODELS);
    const built = store({ modelCatalog: vi.fn().mockRejectedValue(new Error('offline')) });

    expect(await built.store.modelCatalog(key)).toEqual(FALLBACK_MODELS[key]);
    expect(await built.store.modelCatalog('unknown-provider')).toEqual([]);
  });

  it('reads the live catalog with the operator\'s key', async () => {
    const modelCatalogLive = vi.fn().mockResolvedValue({ models: ['live-1'] });
    const built = store({ modelCatalogLive });

    expect(await built.store.modelCatalogLive('anthropic', 'sk-x')).toEqual(['live-1']);
    expect(modelCatalogLive).toHaveBeenCalledWith('anthropic', 'sk-x');
  });

  it('falls back to the configured catalog when the key is rejected', async () => {
    const built = store({
      modelCatalogLive: vi.fn().mockRejectedValue(new Error('401')),
      modelCatalog: vi.fn().mockResolvedValue({ models: ['configured'] }),
    });

    expect(await built.store.modelCatalogLive('anthropic', 'bad-key')).toEqual(['configured']);
  });
});
