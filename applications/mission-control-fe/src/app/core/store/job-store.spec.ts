import { describe, expect, it, vi } from 'vitest';
import { ApiCronJob } from '../hermes-api';
import { apiProfile, liveError, loadedAgentSlices } from '../../testing/store';

const job = (id: string, patch: Partial<ApiCronJob> = {}): ApiCronJob => ({
  id, name: `job ${id}`, prompt: 'do it', schedule: '0 9 * * *', scheduleKind: 'cron',
  deliver: 'local', enabled: true, state: 'scheduled', repeatTimes: null, repeatDone: 0,
  createdAt: 1_000, nextRunAt: 5_000, lastRunAt: null, lastStatus: null, lastError: null,
  skills: [], ...patch,
});

/** A store holding one container and two profiles, with the cron API stubbed. */
const loaded = async (cron: Record<string, unknown>, profiles = ['atlas', 'scribe']) => {
  const slices = await loadedAgentSlices(
    { agents: { cron } }, { profiles: profiles.map(name => apiProfile(name)) });
  return slices;
};

const answer = (jobs: ApiCronJob[], schedulerRunning = true) => ({ jobs, schedulerRunning });

describe('JobStore listing', () => {
  it('unions the schedules of every profile in the container', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('j-1')]))
      .mockResolvedValueOnce(answer([job('j-2')]));
    const { jobs } = await loaded({ list });

    await jobs.refresh();

    expect(list).toHaveBeenCalledTimes(2);
    expect(jobs.jobs().map(j => j.id)).toEqual(['j-1', 'j-2']);
    // each job knows the profile it belongs to, which is how a mutation is addressed
    expect(jobs.jobs().map(j => j.agentId)).toEqual(['a-atlas', 'a-scribe']);
  });

  it('orders the schedule by what runs next', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('late', { nextRunAt: 9_000 })]))
      .mockResolvedValueOnce(answer([job('soon', { nextRunAt: 2_000 })]));
    const { jobs } = await loaded({ list });

    await jobs.refresh();

    expect(jobs.jobs().map(j => j.id)).toEqual(['soon', 'late']);
  });

  it('shows a job that will never run again last, not as due now', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('done', { nextRunAt: null }), job('next')]))
      .mockResolvedValueOnce(answer([]));
    const { jobs } = await loaded({ list });

    await jobs.refresh();

    expect(jobs.jobs().map(j => j.id)).toEqual(['next', 'done']);
    expect(jobs.jobs()[1].nextRun).toBe(Number.POSITIVE_INFINITY);
  });

  it('keeps the rest of the schedule when one profile cannot be read', async () => {
    const list = vi.fn()
      .mockRejectedValueOnce(new Error('container stopped'))
      .mockResolvedValueOnce(answer([job('j-2')]));
    const { jobs } = await loaded({ list });

    await jobs.refresh();

    expect(jobs.jobs().map(j => j.id)).toEqual(['j-2']);
  });

  it('reports the gateway being down, because stored jobs then never fire', async () => {
    const list = vi.fn().mockResolvedValue(answer([job('j-1')], false));
    const { jobs } = await loaded({ list });

    await jobs.refresh();

    expect(jobs.schedulerRunning()).toBe(false);
  });

  it('holds nothing when the container has no profiles to schedule against', async () => {
    const list = vi.fn();
    const { jobs } = await loaded({ list }, []);

    await jobs.refresh();

    expect(jobs.jobs()).toEqual([]);
    expect(list).not.toHaveBeenCalled();
  });

  it('skips an overlapping fan-out rather than doubling the reads', async () => {
    let release!: () => void;
    const pending = new Promise<unknown>(resolve => { release = () => resolve(answer([])); });
    const list = vi.fn().mockReturnValue(pending);
    const { jobs } = await loaded({ list });

    const first = jobs.refresh();
    await jobs.refresh();
    expect(list).toHaveBeenCalledTimes(2);   // the two profiles of the first pass only

    release();
    await first;
  });

  it('shows only the selected container\'s schedule', async () => {
    const list = vi.fn().mockResolvedValue(answer([job('j-1')]));
    const { containers, jobs } = await loaded({ list });
    await jobs.refresh();
    expect(jobs.forSelectedContainer().length).toBeGreaterThan(0);

    containers.select('c-other');

    expect(jobs.forSelectedContainer()).toEqual([]);
  });
});

describe('JobStore mutations', () => {
  it('creates the job on the profile it was assigned to', async () => {
    const create = vi.fn().mockResolvedValue(answer([job('j-new')]));
    const { jobs } = await loaded({ list: vi.fn().mockResolvedValue(answer([])), create },
      ['atlas', 'scribe']);

    expect(await jobs.create('c-1', 'a-scribe', 'digest', '0 9 * * *', 'summarize', 'telegram'))
      .toBe(true);

    expect(create).toHaveBeenCalledWith(
      { hostId: 'dh-local', containerId: 'c-1', name: 'scribe' },
      { schedule: '0 9 * * *', prompt: 'summarize', name: 'digest', deliver: 'telegram' });
    expect(jobs.jobs().map(j => j.id)).toEqual(['j-new']);
  });

  it('sends only the fields an edit actually changed', async () => {
    const update = vi.fn().mockResolvedValue(answer([job('j-1')]));
    const { jobs } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('j-1')])), update,
    }, ['atlas']);
    await jobs.refresh();

    await jobs.update('j-1', { prompt: 'new prompt' });

    expect(update).toHaveBeenCalledWith(expect.anything(), 'j-1', { prompt: 'new prompt' });
  });

  it('pauses a running job and resumes a paused one', async () => {
    const setEnabled = vi.fn().mockResolvedValue(answer([job('j-1', { enabled: false })]));
    const { jobs } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('j-1')])), setEnabled,
    }, ['atlas']);
    await jobs.refresh();

    await jobs.toggle('j-1');

    expect(setEnabled).toHaveBeenCalledWith(expect.anything(), 'j-1', false);
    expect(jobs.byId('j-1')?.enabled).toBe(false);
  });

  it('asks for a run on the next tick without changing the schedule', async () => {
    const runNow = vi.fn().mockResolvedValue(answer([job('j-1')]));
    const { jobs } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('j-1')])), runNow,
    }, ['atlas']);
    await jobs.refresh();

    expect(await jobs.runNow('j-1')).toBe(true);
    expect(runNow).toHaveBeenCalledWith(expect.anything(), 'j-1');
  });

  it('replaces only the owning profile\'s jobs with what the write answered', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('atlas-1')]))
      .mockResolvedValueOnce(answer([job('scribe-1')]));
    const remove = vi.fn().mockResolvedValue(answer([]));
    const { jobs } = await loaded({ list, remove });
    await jobs.refresh();

    await jobs.remove('atlas-1');

    // the other profile's schedule is not this answer's to speak for
    expect(jobs.jobs().map(j => j.id)).toEqual(['scribe-1']);
  });

  it('reports a refused write and leaves the schedule alone', async () => {
    const remove = vi.fn().mockRejectedValue(new Error('job is running'));
    const { ctx, jobs } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('j-1')])), remove,
    }, ['atlas']);
    await jobs.refresh();

    expect(await jobs.remove('j-1')).toBe(false);
    expect(liveError(ctx)).toContain('remove job failed: job is running');
    expect(jobs.byId('j-1')).not.toBeNull();
  });

  it('does nothing for a job it does not hold', async () => {
    const remove = vi.fn();
    const { jobs } = await loaded({ list: vi.fn().mockResolvedValue(answer([])), remove },
      ['atlas']);

    expect(await jobs.remove('gone')).toBe(false);
    expect(await jobs.toggle('gone')).toBe(false);
    expect(await jobs.runNow('gone')).toBe(false);
    expect(remove).not.toHaveBeenCalled();
  });

  it('drops the jobs of a profile or container that is gone', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('atlas-1')]))
      .mockResolvedValueOnce(answer([job('scribe-1')]));
    const { jobs } = await loaded({ list });
    await jobs.refresh();

    jobs.dropByAgent('a-atlas');
    expect(jobs.jobs().map(j => j.id)).toEqual(['scribe-1']);

    jobs.dropByContainer('c-1');
    expect(jobs.jobs()).toEqual([]);
  });
});

describe('JobStore ordering', () => {
  it('keeps the schedule sorted by what runs next, after a write', async () => {
    const list = vi.fn().mockResolvedValue(answer([]));
    const create = vi.fn().mockResolvedValue(answer([
      job('later', { nextRunAt: 9_000 }),
      job('sooner', { nextRunAt: 2_000 }),
    ]));
    const { jobs } = await loaded({ list, create });
    await jobs.refresh();

    expect(await jobs.create('c-1', 'a-atlas', 'digest', '@daily', 'go', 'log')).toBe(true);
    expect(jobs.jobs().map(j => j.id)).toEqual(['sooner', 'later']);
  });

  it('replaces only the writing profile\'s jobs, and re-sorts the union', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce(answer([job('atlas-1', { nextRunAt: 5_000 })]))
      .mockResolvedValueOnce(answer([job('scribe-1', { nextRunAt: 1_000 })]));
    const toggle = vi.fn().mockResolvedValue(answer([job('atlas-2', { nextRunAt: 3_000 })]));
    const { jobs } = await loaded({ list, setEnabled: toggle });
    await jobs.refresh();

    expect(await jobs.toggle('atlas-1')).toBe(true);
    expect(jobs.jobs().map(j => j.id)).toEqual(['scribe-1', 'atlas-2']);
  });
});

describe('JobStore wire tolerance', () => {
  /** Everything hermes may legally leave out of a job row. */
  const sparse = (id: string) => ({
    id, name: null, prompt: null, schedule: null, scheduleKind: 'cron', deliver: null,
    enabled: true, state: 'scheduled', repeatTimes: null, repeatDone: 0,
    createdAt: 1_000, nextRunAt: null, lastRunAt: null, lastStatus: null, lastError: null,
    skills: [],
  }) as unknown as ApiCronJob;

  it('falls back to the job id when hermes recorded no name', async () => {
    const { jobs } = await loaded({ list: vi.fn().mockResolvedValue(answer([sparse('j-1')])) });

    await jobs.refresh();

    expect(jobs.jobs()[0]).toMatchObject({
      name: 'j-1', schedule: '', prompt: '', deliverTo: 'local',
    });
  });

  it('sorts a job that has never been scheduled last, not as due now', async () => {
    const list = vi.fn().mockResolvedValue(answer([
      sparse('never'), job('soon', { nextRunAt: 2_000 }),
    ]));
    const { jobs } = await loaded({ list }, ['atlas']);

    await jobs.refresh();

    expect(jobs.jobs().map(j => j.id)).toEqual(['soon', 'never']);
    expect(jobs.jobs()[1].nextRun).toBe(Number.POSITIVE_INFINITY);
  });

  it('keeps only the two outcomes the page renders', async () => {
    const list = vi.fn().mockResolvedValue(answer([
      job('a', { lastStatus: 'ok' }),
      job('b', { lastStatus: 'fail' }),
      job('c', { lastStatus: 'skipped' as never }),
    ]));
    const { jobs } = await loaded({ list }, ['atlas']);

    await jobs.refresh();

    expect(jobs.jobs().map(j => j.lastStatus)).toEqual(['ok', 'fail', null]);
  });

  it('sends only the fields an edit actually filled in', async () => {
    const update = vi.fn().mockResolvedValue(answer([job('j-1')]));
    const { jobs } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('j-1')])), update,
    });
    await jobs.refresh();

    await jobs.update('j-1', { name: '  digest  ', prompt: '   ', schedule: '@daily' });

    expect(update).toHaveBeenCalledWith(
      expect.anything(), 'j-1', { schedule: '@daily', name: 'digest' });
  });

  it('names the verb by what the toggle is about to do', async () => {
    const setEnabled = vi.fn().mockRejectedValue(new Error('scheduler down'));
    const { jobs, ctx } = await loaded({
      list: vi.fn().mockResolvedValue(answer([job('on'), job('off', { enabled: false })])),
      setEnabled,
    });
    await jobs.refresh();

    await jobs.toggle('on');
    expect(liveError(ctx)).toBe('pause job failed: scheduler down');

    await jobs.toggle('off');
    expect(liveError(ctx)).toBe('resume job failed: scheduler down');
  });

  it('refuses to act on a job it does not hold, and says so', async () => {
    const setEnabled = vi.fn();
    const { jobs, ctx } =
      await loaded({ list: vi.fn().mockResolvedValue(answer([])), setEnabled });

    expect(await jobs.toggle('j-missing')).toBe(false);
    expect(setEnabled).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('job is no longer available');
  });
});
