import { describe, expect, it, vi } from 'vitest';
import { SkillRef } from '../models';
import { skill as buildSkill } from '../../testing/models';
import { apiProfile, liveError, loadedAgentSlices } from '../../testing/store';

const skill = (name: string, enabled = true): SkillRef => buildSkill(name, { enabled });

const PROFILE = apiProfile('atlas', {
  id: 'a-1', skills: [skill('ops'), skill('research', false)],
});

/** What the backend answers a skill write with — the wire shape of a skill is
 *  the domain one, so the same rows travel both ways. */
const refreshed = (skills: SkillRef[]) => ({ ...PROFILE, skills });

/** A store holding one profile, with the API stubbed per test. */
const loaded = async (skills: Record<string, unknown>) => {
  const slices = await loadedAgentSlices({ agents: { skills } }, { profiles: [PROFILE] });
  return { ...slices, store: slices.skills };
};



const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('AgentSkillStore', () => {
  it('applies the skill list a toggle answered with, rather than flipping it locally', async () => {
    const setEnabled = vi.fn().mockResolvedValue(refreshed([skill('ops', false), skill('research', false)]));
    const { agents, store } = await loaded({ setEnabled });

    store.toggle('a-1', 's-ops');
    await settle();

    expect(setEnabled).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' }, 'ops', false);
    expect(agents.byId('a-1')?.skills.map(s => s.enabled)).toEqual([false, false]);
  });

  it('installs by name and takes the refreshed list', async () => {
    const install = vi.fn().mockResolvedValue(refreshed([...(PROFILE.skills as SkillRef[]), skill('web-research')]));
    const { agents, store } = await loaded({ install });

    store.add('a-1', { name: 'web-research', source: 'hub', version: '1', description: '', enabled: true });
    await settle();

    expect(install).toHaveBeenCalledWith(expect.anything(), 'web-research');
    expect(agents.byId('a-1')?.skills.map(s => s.name)).toEqual(['ops', 'research', 'web-research']);
  });

  it('uninstalls by the name behind the row id', async () => {
    const uninstall = vi.fn().mockResolvedValue(refreshed([skill('research', false)]));
    const { agents, store } = await loaded({ uninstall });

    store.remove('a-1', 's-ops');
    await settle();

    expect(uninstall).toHaveBeenCalledWith(expect.anything(), 'ops');
    expect(agents.byId('a-1')?.skills.map(s => s.name)).toEqual(['research']);
  });

  it('does nothing for a profile or a skill it does not have', async () => {
    const calls = { setEnabled: vi.fn(), install: vi.fn(), uninstall: vi.fn() };
    const { store } = await loaded(calls);

    store.toggle('a-gone', 's-ops');
    store.toggle('a-1', 's-gone');
    store.add('a-gone', { name: 'x', source: 'hub', version: '1', description: '', enabled: true });
    store.remove('a-1', 's-gone');
    await settle();

    expect(calls.setEnabled).not.toHaveBeenCalled();
    expect(calls.install).not.toHaveBeenCalled();
    expect(calls.uninstall).not.toHaveBeenCalled();
  });

  it('loads a SKILL.md body with its file list', async () => {
    const content = vi.fn().mockResolvedValue({
      name: 'ops', path: '/opt/data/profiles/atlas/skills/ops', body: '# ops', files: ['SKILL.md'],
    });
    const { store } = await loaded({ content });

    expect(await store.content('a-1', skill('ops'))).toEqual({
      name: 'ops', path: '/opt/data/profiles/atlas/skills/ops', body: '# ops', files: ['SKILL.md'],
    });
  });

  it('treats a body with no file list as having none', async () => {
    const content = vi.fn().mockResolvedValue({ name: 'ops', path: '/p', body: '# ops' });
    const { store } = await loaded({ content });

    expect((await store.content('a-1', skill('ops')))?.files).toEqual([]);
  });

  it('answers null and says why when the body cannot be read', async () => {
    const content = vi.fn().mockRejectedValue(new Error('no such skill'));
    const { ctx, store } = await loaded({ content });

    expect(await store.content('a-1', skill('ops'))).toBeNull();
    expect(liveError(ctx)).toContain('load skill failed: no such skill');
  });

  it('reports whether an edited body was persisted', async () => {
    const updateContent = vi.fn().mockResolvedValue(refreshed((PROFILE.skills as SkillRef[])));
    const { store } = await loaded({ updateContent });

    expect(await store.saveContent('a-1', skill('ops'), '# edited')).toBe(true);
    expect(updateContent).toHaveBeenCalledWith(expect.anything(), 'ops', '# edited');
  });

  it('reports a refused save rather than claiming it landed', async () => {
    const updateContent = vi.fn().mockRejectedValue(new Error('read-only volume'));
    const { ctx, store } = await loaded({ updateContent });

    expect(await store.saveContent('a-1', skill('ops'), '# edited')).toBe(false);
    expect(liveError(ctx)).toContain('save skill failed: read-only volume');
  });

  it('refuses to read or write a skill on a profile it does not have', async () => {
    const { store } = await loaded({});

    expect(await store.content('a-gone', skill('ops'))).toBeNull();
    expect(await store.saveContent('a-gone', skill('ops'), '# x')).toBe(false);
  });
});
