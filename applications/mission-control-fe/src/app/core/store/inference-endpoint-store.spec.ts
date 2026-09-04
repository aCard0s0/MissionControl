import { describe, expect, it, vi } from 'vitest';
import { InferenceEndpoint } from '../models';
import { flush, liveError, testSlices } from '../../testing/store';

const endpoint = (id: string, patch: Partial<InferenceEndpoint> = {}): InferenceEndpoint => ({
  id, name: id, url: `http://${id}:11434`, kind: 'ollama', status: 'connected',
  version: '0.6.4', detail: null, canManageModels: true, ...patch,
});

const store = (endpoints: Record<string, unknown>) => {
  const { ctx, endpoints: store } = testSlices({ endpoints });
  return { ctx, store };
};

describe('InferenceEndpointStore', () => {
  it('keeps the last inventory when a poll fails, rather than blanking it', async () => {
    const list = vi.fn().mockResolvedValueOnce([endpoint('mp-1')]).mockRejectedValue(new Error('down'));
    const built = store({ list });
    await built.store.refresh();

    await built.store.refresh();

    expect(built.store.endpoints().map(p => p.id)).toEqual(['mp-1']);
    expect(liveError(built.ctx)).toBeNull();
  });

  it('re-reads the list after adding one, since the backend assigns the id', async () => {
    const add = vi.fn().mockResolvedValue(endpoint('mp-new'));
    const list = vi.fn().mockResolvedValue([endpoint('mp-new')]);
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

    expect(liveError(built.ctx)).toBe('add endpoint failed: duplicate url');
  });

  it('re-reads the list after removing one, and reports a refused remove', async () => {
    const list = vi.fn().mockResolvedValue([]);
    const ok = store({ remove: vi.fn().mockResolvedValue(undefined), list });
    const bad = store({ remove: vi.fn().mockRejectedValue(new Error('in use')) });

    ok.store.remove('mp-1');
    bad.store.remove('mp-1');
    await flush();

    expect(list).toHaveBeenCalled();
    expect(liveError(bad.ctx)).toBe('remove endpoint failed: in use');
  });

  it('blanks the status while a check runs, then shows what came back', async () => {
    let land!: (value: InferenceEndpoint) => void;
    const check = vi.fn().mockReturnValue(new Promise<InferenceEndpoint>(r => { land = r; }));
    const built = store({ list: vi.fn().mockResolvedValue([endpoint('mp-1')]), check });
    await built.store.refresh();

    built.store.check('mp-1');
    expect(built.store.endpoints()[0].status).toBe('unknown');

    land(endpoint('mp-1', { status: 'error', detail: 'connection refused' }));
    await flush();
    expect(built.store.endpoints()[0].status).toBe('error');
  });

  it('falls back to a full re-read when a check itself fails', async () => {
    const list = vi.fn().mockResolvedValue([endpoint('mp-1')]);
    const built = store({ list, check: vi.fn().mockRejectedValue(new Error('timeout')) });
    await built.store.refresh();

    built.store.check('mp-1');
    await flush();

    expect(liveError(built.ctx)).toBe('endpoint check failed: timeout');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('lets a failed model listing reach the caller, which owns how it shows', async () => {
    // the Models page renders the reason inline where the list would be, and the create
    // dialog's picker holds its own fallback. A toast raised here preempted both, and once
    // it faded the panel claimed "0 listed" for an endpoint that was merely unreachable.
    const built = store({ models: vi.fn().mockRejectedValue(new Error('no such provider')) });

    await expect(built.store.models('mp-1')).rejects.toThrow('no such provider');
    expect(liveError(built.ctx)).toBeNull();
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
});
