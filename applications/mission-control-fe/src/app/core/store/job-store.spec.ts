import { describe, expect, it, vi } from 'vitest';
import { CronJob } from '../models';
import { ContainerStore } from './container-store';
import { JobStore } from './job-store';
import { StoreContext } from './store-context';

const context = () =>
  new StoreContext({ apiBaseUrl: '', dockerSocket: 'unix:///var/run/docker.sock' });

const job = (id: string, patch: Partial<CronJob> = {}): CronJob => ({
  id, containerId: 'c-1', agentId: 'a-1', name: `job ${id}`, schedule: '0 9 * * *',
  prompt: '', deliverTo: 'slack', enabled: true, nextRun: 2_000, lastRun: null,
  lastStatus: 'ok', ...patch,
} as CronJob);

const store = (jobs: CronJob[] = []) => {
  const ctx = context();
  const containers = new ContainerStore(ctx);
  const jobStore = new JobStore(ctx, containers);
  jobStore.jobs.set(jobs);
  return { ctx, containers, jobStore };
};

// Scheduling has no endpoint behind it yet. What this slice must not do is
// pretend otherwise — a job that looks created but never runs is worse than a
// refusal an operator can see.
describe('JobStore', () => {
  it('holds nothing, because nothing serves it', () => {
    expect(new JobStore(context(), new ContainerStore(context())).jobs()).toEqual([]);
  });

  it('says so instead of creating a job that would never run', () => {
    const { ctx, jobStore } = store();

    jobStore.create('c-1', 'a-1', 'nightly', '0 9 * * *', 'do it', 'slack');

    expect(jobStore.jobs()).toEqual([]);
    expect(ctx.liveError()).toContain('not available');
  });

  it('shows only the selected container\'s jobs', () => {
    const { containers, jobStore } = store([job('j-1'), job('j-2', { containerId: 'c-2' })]);

    containers.select('c-1');
    expect(jobStore.forSelectedContainer().map(j => j.id)).toEqual(['j-1']);

    containers.select('c-2');
    expect(jobStore.forSelectedContainer().map(j => j.id)).toEqual(['j-2']);
  });

  it('pauses and resumes one job without touching the rest', () => {
    const { jobStore } = store([job('j-1'), job('j-2')]);

    jobStore.toggle('j-1');
    expect(jobStore.jobs().map(j => j.enabled)).toEqual([false, true]);

    jobStore.toggle('j-1');
    expect(jobStore.jobs().map(j => j.enabled)).toEqual([true, true]);
  });

  it('patches only the fields it was given', () => {
    const { jobStore } = store([job('j-1')]);

    jobStore.update('j-1', { name: 'renamed', schedule: '@daily' });

    expect(jobStore.jobs()[0]).toMatchObject({
      name: 'renamed', schedule: '@daily', prompt: '', deliverTo: 'slack',
    });
  });

  it('drops jobs with the container or the profile they belonged to', () => {
    const { jobStore } = store([job('j-1'), job('j-2', { agentId: 'a-2' }), job('j-3', { containerId: 'c-2' })]);

    jobStore.dropByAgent('a-2');
    expect(jobStore.jobs().map(j => j.id)).toEqual(['j-1', 'j-3']);

    jobStore.dropByContainer('c-1');
    expect(jobStore.jobs().map(j => j.id)).toEqual(['j-3']);
  });

  it('removes one job by id', () => {
    const { jobStore } = store([job('j-1'), job('j-2')]);

    jobStore.remove('j-1');

    expect(jobStore.jobs().map(j => j.id)).toEqual(['j-2']);
  });

  it('re-keys jobs onto the id a container recreate minted', () => {
    const { jobStore } = store([job('j-1'), job('j-2', { containerId: 'c-2' })]);

    jobStore.reassignContainer('c-1', 'c-new');

    expect(jobStore.jobs().map(j => j.containerId)).toEqual(['c-new', 'c-2']);
  });
});
