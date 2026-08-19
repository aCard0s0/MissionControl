import { describe, expect, it, vi } from 'vitest';
import { apiContainer, apiProfile, storeSlices, stubBackend } from '../../testing/store';

/**
 * The one rule in the store layer that spans slices: a profile takes its jobs,
 * tasks, webhooks and cached credentials with it. Each of those lives in a slice
 * that knows nothing about the others, so the cascade is only correct if this
 * collaborator runs — which is what these check.
 */
const loaded = async (agentsApi: Record<string, unknown>) => {
  const slices = storeSlices();
  stubBackend(slices.ctx, {
    containers: { list: vi.fn().mockResolvedValue([apiContainer()]) },
    agents: { list: vi.fn().mockResolvedValue([apiProfile('atlas')]), ...agentsApi },
  });
  await slices.containers.refresh();
  await slices.agents.refresh();
  return slices;
};

describe('AgentRemoval', () => {
  it('forgets everything keyed to the profile, once the backend has agreed', async () => {
    const slices = await loaded({ remove: vi.fn().mockResolvedValue(undefined) });
    const dropped = [
      vi.spyOn(slices.jobs, 'dropByAgent'),
      vi.spyOn(slices.board, 'dropByAgent'),
      vi.spyOn(slices.webhooks, 'dropByAgent'),
      vi.spyOn(slices.setup, 'forget'),
    ];

    expect(await slices.removal.remove('a-atlas')).toBe(true);

    expect(slices.agents.byId('a-atlas')).toBeNull();
    for (const spy of dropped) expect(spy).toHaveBeenCalledWith('a-atlas');
  });

  it('drops nothing local when the backend refused the delete', async () => {
    const slices = await loaded({ remove: vi.fn().mockRejectedValue(new Error('profile busy')) });
    const jobs = vi.spyOn(slices.jobs, 'dropByAgent');
    const setup = vi.spyOn(slices.setup, 'forget');

    expect(await slices.removal.remove('a-atlas')).toBe(false);

    expect(slices.agents.byId('a-atlas')).not.toBeNull();
    expect(jobs).not.toHaveBeenCalled();
    expect(setup).not.toHaveBeenCalled();
    expect(slices.ctx.liveError()).toBe('remove profile failed: profile busy');
  });
});
