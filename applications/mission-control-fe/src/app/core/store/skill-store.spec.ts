import { describe, expect, it, vi } from 'vitest';
import { ApiSkill } from '../hermes-api';
import { liveError, liveNotice, testSlices } from '../../testing/store';

const apiSkill = (id: string, patch: Partial<ApiSkill> = {}): ApiSkill => ({
  id, kind: 'local', name: `skill-${id}`, description: 'reads pdfs', category: 'docs',
  repoUrl: null, version: '1.0', files: [{ path: 'SKILL.md', body: '# pdf' }],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const AGENT = { hostId: 'dh-1', containerId: 'c1', name: 'ops' };

/** A library holding `skills`. */
const loaded = async (skills: ApiSkill[], api: Record<string, unknown> = {}) => {
  const { ctx, skillLibrary: store } = testSlices({
    skills: { list: vi.fn().mockResolvedValue(skills), ...api },
  });
  await store.refresh();
  return { ctx, store };
};

describe('SkillStore', () => {
  it('reads the library and fills in the fields the wire leaves null', async () => {
    const { store } = await loaded([
      apiSkill('s-1', { description: null, repoUrl: null, version: null, category: '' }),
    ]);

    expect(store.skills()[0]).toMatchObject({
      id: 's-1', description: '', repoUrl: '', version: '', category: 'general',
    });
  });

  it('reads a hub row that carries no files as an empty file list', async () => {
    // the backend stores NULL rather than [] for a hub row, and the page branches on
    // the kind rather than on the file count
    const { store } = await loaded([apiSkill('s-1', { kind: 'hub', files: null })]);

    expect(store.skills()[0]).toMatchObject({ kind: 'hub', files: [] });
  });

  it('keeps the last library when a read fails, rather than emptying the page', async () => {
    const { store, ctx } = await loaded([apiSkill('s-1')], {
      list: vi.fn().mockResolvedValueOnce([apiSkill('s-1')]).mockRejectedValue(new Error('offline')),
    });

    await store.refresh();

    expect(store.skills().map(s => s.id)).toEqual(['s-1']);
    expect(liveError(ctx)).toBeNull();
  });

  it('lists every category in use once, sorted, for the filter chips', async () => {
    const { store } = await loaded([
      apiSkill('s-1', { category: 'writing' }),
      apiSkill('s-2', { category: 'docs' }),
      apiSkill('s-3', { category: 'docs' }),
    ]);

    expect(store.categories()).toEqual(['docs', 'writing']);
  });

  it('reports a failed save rather than pretending the skill was kept', async () => {
    const { store, ctx } = await loaded([], {
      create: vi.fn().mockRejectedValue(new Error('name taken')),
    });

    const id = await store.save({
      kind: 'local', name: 'pdf', description: '', category: '', repoUrl: '', version: '',
      files: [{ path: 'SKILL.md', body: '# pdf' }],
    });

    expect(id).toBe('');
    expect(store.skills()).toEqual([]);
    expect(liveError(ctx)).toContain('save skill');
  });

  it('keeps a deleted skill on the page when the delete failed', async () => {
    const { store, ctx } = await loaded([apiSkill('s-1')], {
      remove: vi.fn().mockRejectedValue(new Error('locked')),
    });

    expect(await store.remove('s-1')).toBe(false);
    expect(store.skills().map(s => s.id)).toEqual(['s-1']);
    expect(liveError(ctx)).toContain('delete skill');
  });

  it('reports a failed deploy rather than a silent no-op', async () => {
    const { store, ctx } = await loaded([apiSkill('s-1')], {
      deploy: vi.fn().mockRejectedValue(new Error('container gone')),
    });

    expect(await store.deploy('s-1', AGENT)).toBe(false);
    expect(liveError(ctx)).toContain('deploy skill');
  });

  it('names the files an import left behind, rather than reporting a clean success', async () => {
    // a partial import that said "saved" would be found broken on the next deploy
    const { store, ctx } = await loaded([], {
      importFrom: vi.fn().mockResolvedValue({
        skill: apiSkill('s-9', { name: 'curated' }), skipped: ['logo.png', 'font.ttf'],
      }),
    });

    expect(await store.importFrom(AGENT, 'curated')).toBe(true);
    expect(store.skills().map(s => s.id)).toEqual(['s-9']);
    expect(liveNotice(ctx)).toContain('2 non-text files');
    expect(liveNotice(ctx)).toContain('logo.png');
  });

  it('confirms a clean import without mentioning files', async () => {
    const { store, ctx } = await loaded([], {
      importFrom: vi.fn().mockResolvedValue({
        skill: apiSkill('s-9', { name: 'curated' }), skipped: null,
      }),
    });

    await store.importFrom(AGENT, 'curated');

    expect(liveNotice(ctx)).toBe('saved curated to the library');
  });

  it('puts a saved skill at the front, which is the order the backend lists in', async () => {
    const { store } = await loaded([apiSkill('s-1'), apiSkill('s-2')], {
      update: vi.fn().mockResolvedValue(apiSkill('s-2', { name: 'renamed' })),
    });

    await store.save({
      kind: 'local', name: 'renamed', description: '', category: 'docs', repoUrl: '',
      version: '1.0', files: [{ path: 'SKILL.md', body: '# pdf' }],
    }, 's-2');

    expect(store.skills().map(s => s.id)).toEqual(['s-2', 's-1']);
  });
});
