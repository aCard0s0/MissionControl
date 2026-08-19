import { describe, expect, it, vi } from 'vitest';
import { ApiProfileTemplate } from '../hermes-api';
import { TemplateStore } from './template-store';
import { apiProfile, loadedAgentSlices } from '../../testing/store';

const template = (id: string, patch: Partial<ApiProfileTemplate> = {}): ApiProfileTemplate => ({
  id, name: id, description: 'SRE copilot', provider: 'anthropic', model: 'claude-fable-5',
  baseUrl: '', cwd: '/opt/data', soul: '# SOUL', memory: '# MEMORY',
  skills: ['ops'], mcpServers: [], secrets: [], createdAt: 1, updatedAt: 2, ...patch,
});

/** The template slice over a container holding one profile. */
const loaded = async (templates: Record<string, unknown>, list: ApiProfileTemplate[] = []) => {
  const slices = await loadedAgentSlices(
    { templates: { list: vi.fn().mockResolvedValue(list), ...templates } },
    { profiles: [apiProfile('atlas')] });
  const store = new TemplateStore(slices.ctx, slices.containers, slices.agents);
  await store.refresh();
  return { ...slices, store };
};

describe('TemplateStore', () => {
  it('answers byId, and null for one it does not hold', async () => {
    const { store } = await loaded({}, [template('pt-ops')]);

    expect(store.byId('pt-ops')?.name).toBe('pt-ops');
    expect(store.byId('pt-missing')).toBeNull();
    expect(store.byId(null)).toBeNull();
  });

  it('keeps the last list when a read fails', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([template('pt-ops')])
      .mockRejectedValue(new Error('offline'));
    const { store, ctx } = await loaded({ list }, [template('pt-ops')]);

    await store.refresh();

    expect(store.templates().map(t => t.id)).toEqual(['pt-ops']);
    expect(ctx.liveError()).toBeNull();
  });

  it('creates when given no id and updates when given one', async () => {
    const create = vi.fn().mockResolvedValue(template('pt-new'));
    const update = vi.fn().mockResolvedValue(template('pt-ops', { description: 'edited' }));
    const { store } = await loaded({ create, update }, [template('pt-ops')]);

    expect(await store.save({ name: 'pt-new' } as never)).toBe('pt-new');
    expect(await store.save({ name: 'pt-ops' } as never, 'pt-ops')).toBe('pt-ops');
    expect(store.byId('pt-ops')?.description).toBe('edited');
    expect(store.templates().map(t => t.id)).toEqual(['pt-ops', 'pt-new']);
  });

  it('answers an empty id and says why a save failed', async () => {
    const { store, ctx } = await loaded({ create: vi.fn().mockRejectedValue(new Error('name taken')) });

    expect(await store.save({ name: 'pt-new' } as never)).toBe('');
    expect(ctx.liveError()).toBe('save template failed: name taken');
  });

  it('drops a template only after the backend confirms the delete', async () => {
    const { store } = await loaded({ remove: vi.fn().mockResolvedValue(undefined) }, [template('pt-ops')]);

    await store.remove('pt-ops');

    expect(store.templates()).toEqual([]);
  });

  it('keeps a template the delete failed on', async () => {
    const { store, ctx } = await loaded(
      { remove: vi.fn().mockRejectedValue(new Error('in use')) }, [template('pt-ops')]);

    await store.remove('pt-ops');

    expect(store.templates().map(t => t.id)).toEqual(['pt-ops']);
    expect(ctx.liveError()).toBe('delete template failed: in use');
  });

  it('deploys into a container and folds the new profile in', async () => {
    const deploy = vi.fn().mockResolvedValue(apiProfile('sre'));
    const { store, agents } = await loaded({ deploy }, [template('pt-ops')]);

    expect(await store.deploy('pt-ops', 'c-1', 'sre')).toBe('a-sre');
    expect(deploy).toHaveBeenCalledWith('pt-ops',
      { hostId: 'dh-local', containerId: 'c-1', name: 'sre' });
    expect(agents.byId('a-sre')?.name).toBe('sre');
  });

  it('refuses to deploy an unknown template or into an unknown container', async () => {
    const deploy = vi.fn();
    const { store } = await loaded({ deploy }, [template('pt-ops')]);

    expect(await store.deploy('pt-missing', 'c-1', 'sre')).toBe('');
    expect(await store.deploy('pt-ops', 'c-missing', 'sre')).toBe('');
    expect(deploy).not.toHaveBeenCalled();
  });

  it('reports a deploy the backend refused', async () => {
    const { store, ctx } = await loaded(
      { deploy: vi.fn().mockRejectedValue(new Error('profile exists')) }, [template('pt-ops')]);

    expect(await store.deploy('pt-ops', 'c-1', 'sre')).toBe('');
    expect(ctx.liveError()).toBe('deploy template failed: profile exists');
  });

  it('captures a running profile into a new template', async () => {
    const capture = vi.fn().mockResolvedValue(template('pt-atlas'));
    const { store } = await loaded({ capture });

    expect(await store.capture('a-atlas', 'atlas-template')).toBe('pt-atlas');
    expect(capture).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'atlas' }), 'atlas-template');
    expect(store.byId('pt-atlas')).not.toBeNull();
  });

  it('refuses to capture a profile it does not hold, and reports a refused capture', async () => {
    const capture = vi.fn().mockRejectedValue(new Error('profile busy'));
    const { store, ctx } = await loaded({ capture });

    expect(await store.capture('a-ghost')).toBe('');
    expect(capture).not.toHaveBeenCalled();

    expect(await store.capture('a-atlas')).toBe('');
    expect(ctx.liveError()).toBe('capture template failed: profile busy');
  });
});
