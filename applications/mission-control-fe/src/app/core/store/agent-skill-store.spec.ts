import { describe, expect, it, vi } from 'vitest';
import { AgentProfile, SkillRef } from '../models';
import { AgentSkillStore } from './agent-skill-store';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

const CONTAINER = {
  id: 'c-1', name: 'hermes-prod', shortId: 'c1', hostId: 'dh-local', status: 'running',
  image: 'hermes', version: 'v1', startedAt: 1, sizeRootFsGb: 1, profiles: ['atlas'],
};

const skill = (name: string, enabled = true): SkillRef =>
  ({ id: `s-${name}`, name, source: 'bundled', version: '1', description: '', enabled });

const PROFILE = {
  id: 'a-1', containerId: 'c-1', name: 'atlas', role: '', state: 'idle', provider: 'nous',
  model: 'm', apiKeyMasked: '', cwd: '', soul: '', memoryMd: '', configYaml: '',
  skills: [skill('ops'), skill('research', false)], mcp: [], integrations: [], lastActive: 1,
};

/** A store holding one profile, with the API stubbed per test. */
const loaded = async (skills: Record<string, unknown>) => {
  const ctx = new StoreContext({ apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' });
  const containers = new ContainerStore(ctx);
  const agents = new AgentStore(ctx, containers);
  (ctx as unknown as { api: unknown }).api = {
    containers: { list: vi.fn().mockResolvedValue([CONTAINER]) },
    agents: { list: vi.fn().mockResolvedValue([PROFILE]), skills },
  };
  await containers.refresh();
  await agents.refresh();
  return { ctx, agents, store: new AgentSkillStore(ctx, agents) };
};

/** What the backend answers a skill write with: the whole refreshed profile. */
const answering = (skills: SkillRef[]) => ({ ...PROFILE, skills });

const settle = () => new Promise(resolve => setTimeout(resolve, 0));

describe('AgentSkillStore', () => {
  it('applies the skill list a toggle answered with, rather than flipping it locally', async () => {
    const setEnabled = vi.fn().mockResolvedValue(answering([skill('ops', false), skill('research', false)]));
    const { agents, store } = await loaded({ setEnabled });

    store.toggle('a-1', 's-ops');
    await settle();

    expect(setEnabled).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'c-1', name: 'atlas' }, 'ops', false);
    expect(agents.byId('a-1')?.skills.map(s => s.enabled)).toEqual([false, false]);
  });

  it('installs by name and takes the refreshed list', async () => {
    const install = vi.fn().mockResolvedValue(answering([...PROFILE.skills, skill('web-research')]));
    const { agents, store } = await loaded({ install });

    store.add('a-1', { name: 'web-research', source: 'hub', version: '1', description: '', enabled: true });
    await settle();

    expect(install).toHaveBeenCalledWith(expect.anything(), 'web-research');
    expect(agents.byId('a-1')?.skills.map(s => s.name)).toEqual(['ops', 'research', 'web-research']);
  });

  it('uninstalls by the name behind the row id', async () => {
    const uninstall = vi.fn().mockResolvedValue(answering([skill('research', false)]));
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
    expect(ctx.liveError()).toContain('load skill failed: no such skill');
  });

  it('reports whether an edited body was persisted', async () => {
    const updateContent = vi.fn().mockResolvedValue(answering(PROFILE.skills));
    const { store } = await loaded({ updateContent });

    expect(await store.saveContent('a-1', skill('ops'), '# edited')).toBe(true);
    expect(updateContent).toHaveBeenCalledWith(expect.anything(), 'ops', '# edited');
  });

  it('reports a refused save rather than claiming it landed', async () => {
    const updateContent = vi.fn().mockRejectedValue(new Error('read-only volume'));
    const { ctx, store } = await loaded({ updateContent });

    expect(await store.saveContent('a-1', skill('ops'), '# edited')).toBe(false);
    expect(ctx.liveError()).toContain('save skill failed: read-only volume');
  });

  it('refuses to read or write a skill on a profile it does not have', async () => {
    const { store } = await loaded({});

    expect(await store.content('a-gone', skill('ops'))).toBeNull();
    expect(await store.saveContent('a-gone', skill('ops'), '# x')).toBe(false);
  });
});
