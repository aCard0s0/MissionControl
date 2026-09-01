import { describe, expect, it, vi } from 'vitest';
import { ApiDeployedPart, ApiSkillGuide } from '../hermes-api';
import { liveError, testSlices } from '../../testing/store';

const apiGuide = (id: string, patch: Partial<ApiSkillGuide> = {}): ApiSkillGuide => ({
  id, name: `guide-${id}`, description: 'triage a broken export', body: 'Read the log first.',
  category: 'docs', skillIds: ['s-1'], mcpServerIds: ['m-1'],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const part = (patch: Partial<ApiDeployedPart> = {}): ApiDeployedPart =>
  ({ kind: 'skill', name: 'pdf-tools', status: 'deployed', detail: null, ...patch });

const AGENT = { hostId: 'dh-1', containerId: 'c1', name: 'ops' };

const loaded = async (guides: ApiSkillGuide[], api: Record<string, unknown> = {}) => {
  const { ctx, guides: store } = testSlices({
    guides: { list: vi.fn().mockResolvedValue(guides), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('SkillGuideStore', () => {
  it('reads the library and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([
      apiGuide('g-1', { description: null, skillIds: null, mcpServerIds: null, category: '' }),
    ]);

    expect(store.guides()[0]).toMatchObject({
      id: 'g-1', description: '', skillIds: [], mcpServerIds: [], category: 'general',
    });
  });

  it('keeps the last library when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiGuide('g-1')], {
      list: vi.fn().mockResolvedValueOnce([apiGuide('g-1')]).mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.guides().map(g => g.id)).toEqual(['g-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('returns the per-part report so a half-landed deploy is not read as a success', async () => {
    const { store } = await loaded([apiGuide('g-1')], {
      deploy: vi.fn().mockResolvedValue({
        profile: {},
        parts: [
          part(),
          part({ kind: 'mcp', name: 'postgres', status: 'failed', detail: 'not running' }),
        ],
      }),
    });

    const report = await store.deploy('g-1', AGENT);

    expect(report).toEqual([
      { kind: 'skill', name: 'pdf-tools', status: 'deployed', detail: '' },
      { kind: 'mcp', name: 'postgres', status: 'failed', detail: 'not running' },
    ]);
  });

  it('reads a status this build does not know as failed, not as success', async () => {
    // the safe direction: the operator is deciding whether the deploy actually worked
    const { store } = await loaded([apiGuide('g-1')], {
      deploy: vi.fn().mockResolvedValue({ profile: {}, parts: [part({ status: 'quantum' })] }),
    });

    expect((await store.deploy('g-1', AGENT))?.[0].status).toBe('failed');
  });

  it('answers null when the deploy request itself failed', async () => {
    const { store, ctx } = await loaded([apiGuide('g-1')], {
      deploy: vi.fn().mockRejectedValue(new Error('container gone')),
    });

    expect(await store.deploy('g-1', AGENT)).toBeNull();
    expect(liveError(ctx)).toContain('deploy guide');
  });

  it('reports a failed save rather than pretending the guide was kept', async () => {
    const { store, ctx } = await loaded([], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    const id = await store.save({
      name: 'pdf-triage', description: '', body: 'x', category: '',
      skillIds: [], mcpServerIds: [],
    });

    expect(id).toBe('');
    expect(store.guides()).toEqual([]);
    expect(liveError(ctx)).toContain('save guide');
  });

  it('keeps a deleted guide on the page when the delete failed', async () => {
    const { store, ctx } = await loaded([apiGuide('g-1')], {
      remove: vi.fn().mockRejectedValue(new Error('locked')),
    });

    expect(await store.remove('g-1')).toBe(false);
    expect(store.guides().map(g => g.id)).toEqual(['g-1']);
    expect(liveError(ctx)).toContain('delete guide');
  });

  it('lists every category in use once, sorted, for the filter chips', async () => {
    const { store } = await loaded([
      apiGuide('g-1', { category: 'writing' }),
      apiGuide('g-2', { category: 'docs' }),
      apiGuide('g-3', { category: 'docs' }),
    ]);

    expect(store.categories()).toEqual(['docs', 'writing']);
  });
});
