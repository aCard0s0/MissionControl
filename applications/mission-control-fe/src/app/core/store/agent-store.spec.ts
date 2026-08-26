import { describe, expect, it, vi } from 'vitest';
import { ApiAgentProfile } from '../hermes-api';
import { apiContainer, apiProfile, flush, liveError, liveNotice, testSlices } from '../../testing/store';

const mcp = (name: string, status: string) => ({
  id: `m-${name}`, name, transport: 'http', status, tools: 2, latencyMs: 10,
});

/** Containers plus the `/api/agents` client, with nothing loaded yet. */
const built = async (agentsApi: Record<string, unknown>, containers = [apiContainer()]) => {
  const slices = testSlices({
    containers: { list: vi.fn().mockResolvedValue(containers) },
    agents: agentsApi,
  });
  await slices.containers.refresh();
  return slices;
};

describe('AgentStore refresh', () => {
  it('unions one listing per container', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiProfile('atlas')])
      .mockResolvedValueOnce([apiProfile('scribe', { id: 'a-scribe', containerId: 'c-2' })]);
    const { agents } = await built({ list }, [apiContainer(), apiContainer({ id: 'c-2' })]);

    await agents.refresh();

    expect(agents.agents().map(a => a.id)).toEqual(['a-atlas', 'a-scribe']);
  });

  it('empties the roster when there are no containers at all', async () => {
    const list = vi.fn();
    const { agents } = await built({ list }, []);

    await agents.refresh();

    expect(agents.agents()).toEqual([]);
    expect(list).not.toHaveBeenCalled();
  });

  it('does not ask a stopped container for its profiles, but keeps the ones it had', async () => {
    const list = vi.fn().mockResolvedValue([apiProfile('atlas')]);
    const { agents, containers } = await built({ list });
    await agents.refresh();

    (containers.containers as unknown as { set(v: unknown): void })
      .set(containers.containers().map(c => ({ ...c, status: 'stopped' as const })));
    await agents.refresh();

    expect(list).toHaveBeenCalledTimes(1);
    expect(agents.agents().map(a => a.id)).toEqual(['a-atlas']);
  });

  it('keeps a container\'s last known profiles when its listing fails', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiProfile('atlas')])
      .mockRejectedValue(new Error('exec timed out'));
    const { agents } = await built({ list });
    await agents.refresh();

    await agents.refresh();

    expect(agents.agents().map(a => a.id)).toEqual(['a-atlas']);
  });

  it('skips a tick rather than overlapping two fan-outs', async () => {
    let land!: (value: ApiAgentProfile[]) => void;
    const list = vi.fn().mockReturnValue(new Promise<ApiAgentProfile[]>(r => { land = r; }));
    const { agents } = await built({ list });

    const first = agents.refresh();
    await agents.refresh();
    expect(list).toHaveBeenCalledTimes(1);

    land([apiProfile('atlas')]);
    await first;
  });

  it('does not knock an in-flight probe back to unknown', async () => {
    const list = vi.fn().mockResolvedValue([apiProfile('atlas', { mcp: [mcp('github', 'unknown')] })]);
    const { agents } = await built({ list });
    await agents.refresh();
    agents.update('a-atlas', a => ({
      ...a, mcp: a.mcp.map(m => ({ ...m, status: 'checking' as const })),
    }));

    await agents.refresh();

    expect(agents.byId('a-atlas')?.mcp[0].status).toBe('checking');
  });

  it('takes a real probe result over the one that was in flight', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiProfile('atlas', { mcp: [mcp('github', 'unknown')] })])
      .mockResolvedValue([apiProfile('atlas', { mcp: [mcp('github', 'connected')] })]);
    const { agents } = await built({ list });
    await agents.refresh();
    agents.update('a-atlas', a => ({
      ...a, mcp: a.mcp.map(m => ({ ...m, status: 'checking' as const })),
    }));

    await agents.refresh();

    expect(agents.byId('a-atlas')?.mcp[0].status).toBe('connected');
  });
});

describe('AgentStore identity', () => {
  it('addresses a profile by its container\'s host, not by the profile id', async () => {
    const { agents } = await built({ list: vi.fn().mockResolvedValue([apiProfile('atlas')]) });
    await agents.refresh();

    expect(agents.resolve('a-atlas')?.ref)
      .toEqual({ hostId: 'dh-local', containerId: 'c-1', name: 'atlas' });
  });

  it('refuses to address a profile whose container is gone', async () => {
    const { agents } = await built({
      list: vi.fn().mockResolvedValue([apiProfile('atlas', { containerId: 'c-gone' })]),
    });
    await agents.refresh();

    expect(agents.byId('a-atlas')).not.toBeNull();
    expect(agents.resolve('a-atlas')).toBeNull();
    expect(agents.resolve('a-ghost')).toBeNull();
  });
});

describe('AgentStore create', () => {
  it('creates against the container\'s host and adopts what came back', async () => {
    const create = vi.fn().mockResolvedValue(apiProfile('sre', { id: 'a-sre' }));
    const { agents } = await built({ list: vi.fn().mockResolvedValue([]), create });

    expect(await agents.create({
      containerId: 'c-1', name: 'sre', provider: 'anthropic', model: 'm', apiKey: 'sk-x',
    })).toBe('a-sre');
    expect(create).toHaveBeenCalledWith(expect.objectContaining({
      hostId: 'dh-local', containerId: 'c-1', name: 'sre', provider: 'anthropic',
      model: 'm', apiKey: 'sk-x', cloneFrom: undefined, fromTemplateId: undefined,
    }));
    expect(agents.byId('a-sre')?.name).toBe('sre');
  });

  it('clones by profile name, because that is what the backend copies from', async () => {
    const create = vi.fn().mockResolvedValue(apiProfile('sre', { id: 'a-sre' }));
    const { agents } = await built({
      list: vi.fn().mockResolvedValue([apiProfile('atlas')]), create,
    });
    await agents.refresh();

    await agents.create({
      containerId: 'c-1', name: 'sre', provider: 'anthropic', model: 'm', apiKey: '',
      cloneFrom: 'a-atlas',
    });

    expect(create).toHaveBeenCalledWith(expect.objectContaining({ cloneFrom: 'atlas' }));
  });

  it('refuses to create in a container it does not hold, and says why', async () => {
    const create = vi.fn();
    const { agents, ctx } = await built({ list: vi.fn().mockResolvedValue([]), create });

    expect(await agents.create({
      containerId: 'c-missing', name: 'sre', provider: 'anthropic', model: 'm', apiKey: '',
    })).toBe('');
    expect(create).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('container is no longer available');
  });

  it('answers an empty id and says why a create failed', async () => {
    const { agents, ctx } = await built({
      list: vi.fn().mockResolvedValue([]),
      create: vi.fn().mockRejectedValue(new Error('profile exists')),
    });

    expect(await agents.create({
      containerId: 'c-1', name: 'sre', provider: 'anthropic', model: 'm', apiKey: '',
    })).toBe('');
    expect(liveError(ctx)).toBe('create profile failed: profile exists');
    expect(liveNotice(ctx)).toBeNull();
  });

  it('replaces a row a concurrent poll already picked up', async () => {
    const { agents } = await built({ list: vi.fn().mockResolvedValue([apiProfile('atlas')]) });
    await agents.refresh();

    agents.adopt(apiProfile('atlas', { role: 'SRE' }));

    expect(agents.agents().length).toBe(1);
    expect(agents.byId('a-atlas')?.role).toBe('SRE');
  });
});

describe('AgentStore remove', () => {
  it('drops the profile once the backend agrees', async () => {
    const remove = vi.fn().mockResolvedValue(undefined);
    const { agents } = await built({ list: vi.fn().mockResolvedValue([apiProfile('atlas')]), remove });
    await agents.refresh();

    expect(await agents.remove('a-atlas')).toBe(true);
    expect(agents.byId('a-atlas')).toBeNull();
  });

  it('keeps the profile when the delete failed', async () => {
    const { agents, ctx } = await built({
      list: vi.fn().mockResolvedValue([apiProfile('atlas')]),
      remove: vi.fn().mockRejectedValue(new Error('profile busy')),
    });
    await agents.refresh();

    expect(await agents.remove('a-atlas')).toBe(false);
    expect(agents.byId('a-atlas')).not.toBeNull();
    expect(liveError(ctx)).toBe('remove profile failed: profile busy');
  });

  it('says so for a profile it cannot address', async () => {
    const remove = vi.fn();
    const { agents, ctx } = await built({ list: vi.fn().mockResolvedValue([]), remove });

    expect(await agents.remove('a-ghost')).toBe(false);
    expect(remove).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('profile is no longer available');
  });
});

describe('AgentStore reads', () => {
  it('returns the gateway log newest first, tagged with the profile that owns it', async () => {
    const logs = vi.fn().mockResolvedValue([
      { ts: 1, level: 'info', source: 'gateway', msg: 'older' },
      { ts: 9, level: 'warn', source: 'gateway', msg: 'newer' },
    ]);
    const { agents } = await built({ list: vi.fn().mockResolvedValue([apiProfile('atlas')]), logs });
    await agents.refresh();

    const lines = await agents.logTail('a-atlas', 50);

    expect(lines.map(l => l.msg)).toEqual(['newer', 'older']);
    expect(lines.every(l => l.agentId === 'a-atlas')).toBe(true);
    expect(logs).toHaveBeenCalledWith(expect.objectContaining({ name: 'atlas' }), 50);
  });

  it('answers an empty log for a profile it cannot address', async () => {
    const logs = vi.fn();
    const { agents } = await built({ list: vi.fn().mockResolvedValue([]), logs });

    expect(await agents.logTail('a-ghost')).toEqual([]);
    expect(logs).not.toHaveBeenCalled();
  });

  it('writes a fresh integration probe onto the profile', async () => {
    const integrations = vi.fn().mockResolvedValue([
      { kind: 'filesystem', status: 'up', detail: '/srv (rw)' },
    ]);
    const { agents } = await built({
      list: vi.fn().mockResolvedValue([apiProfile('atlas')]), integrations,
    });
    await agents.refresh();

    agents.pingIntegrations('a-atlas');
    await flush();

    expect(agents.byId('a-atlas')?.integrations)
      .toEqual([{ kind: 'filesystem', status: 'up', detail: '/srv (rw)' }]);
  });

  it('says why an integration probe failed, and ignores an unknown profile', async () => {
    const integrations = vi.fn().mockRejectedValue(new Error('container stopped'));
    const { agents, ctx } = await built({
      list: vi.fn().mockResolvedValue([apiProfile('atlas')]), integrations,
    });
    await agents.refresh();

    agents.pingIntegrations('a-ghost');
    agents.pingIntegrations('a-atlas');
    await flush();

    expect(integrations).toHaveBeenCalledTimes(1);
    expect(liveError(ctx)).toBe('integrations refresh failed: container stopped');
  });
});
