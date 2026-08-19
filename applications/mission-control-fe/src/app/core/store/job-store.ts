import { WritableSignal, computed, signal } from '@angular/core';
import { ApiCronJob, ApiCronJobRequest } from '../hermes-api';
import { CronJob } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/**
 * Scheduled prompts, which hermes owns per profile.
 *
 * The page is container-scoped and hermes is profile-scoped, so listing fans out over
 * the container's profiles — one read each, capped like the other pollers — and every
 * mutation is addressed to the profile that owns the job. A job carries its profile in
 * `agentId` for exactly that reason.
 *
 * Nothing here computes a schedule or an id: hermes parses the expression, mints the id
 * and decides the next run, and each call answers with the schedule it now holds.
 */
export class JobStore {
  readonly jobs: WritableSignal<CronJob[]> = signal([]);

  /**
   * False when the container's gateway is down. Hermes stores jobs either way but
   * nothing fires them, which is worth saying on the page rather than leaving an
   * operator to wonder why a job never ran.
   */
  readonly schedulerRunning = signal(true);

  readonly forSelectedContainer = computed(() =>
    this.jobs().filter(j => j.containerId === this.containers.selectedContainerId()));

  private refreshInFlight = false;

  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
  ) {
    // the schedule on screen belongs to the selected container's profiles, so a
    // switch re-reads rather than waiting out the poll
    containers.onSelect(() => void this.refresh());
  }

  /** One read per profile in the selected container, unioned. */
  async refresh(): Promise<void> {
    if (this.refreshInFlight) return;   // skip a tick rather than overlap fan-outs
    this.refreshInFlight = true;
    try {
      const profiles = this.agents.forSelectedContainer();
      if (!profiles.length) {
        this.jobs.set([]);
        return;
      }
      let anyScheduler = false;
      const lists = await this.ctx.mapPool(profiles, 6, async profile => {
        const resolved = this.agents.resolve(profile.id);
        if (!resolved) return [];
        try {
          const answer = await this.ctx.api.agents.cron.list(resolved.ref);
          anyScheduler = anyScheduler || answer.schedulerRunning;
          return answer.jobs.map(job => toCronJob(job, profile.containerId, profile.id));
        } catch {
          return [];   // a stopped or unreachable profile keeps the rest of the schedule
        }
      });
      this.jobs.set(lists.flat().sort((a, b) => a.nextRun - b.nextRun));
      this.schedulerRunning.set(anyScheduler);
    } finally {
      this.refreshInFlight = false;
    }
  }

  create(
    containerId: string, agentId: string, name: string, schedule: string,
    prompt: string, deliverTo: string,
  ): Promise<boolean> {
    return this.mutate(agentId, 'schedule job', ref =>
      this.ctx.api.agents.cron.create(ref, request({ schedule, prompt, name, deliver: deliverTo })));
  }

  update(id: string, patch: Partial<CronJob>): Promise<boolean> {
    const job = this.byId(id);
    if (!job) return Promise.resolve(this.ctx.gone('job'));
    return this.mutate(job.agentId, 'job update', ref =>
      this.ctx.api.agents.cron.update(ref, id, request({
        schedule: patch.schedule, prompt: patch.prompt, name: patch.name,
        deliver: patch.deliverTo,
      })));
  }

  toggle(id: string): Promise<boolean> {
    const job = this.byId(id);
    if (!job) return Promise.resolve(this.ctx.gone('job'));
    return this.mutate(job.agentId, job.enabled ? 'pause job' : 'resume job',
      ref => this.ctx.api.agents.cron.setEnabled(ref, id, !job.enabled));
  }

  /** Asks for the job on the next scheduler tick rather than at its schedule. */
  runNow(id: string): Promise<boolean> {
    const job = this.byId(id);
    if (!job) return Promise.resolve(this.ctx.gone('job'));
    return this.mutate(job.agentId, 'run job', ref => this.ctx.api.agents.cron.runNow(ref, id));
  }

  remove(id: string): Promise<boolean> {
    const job = this.byId(id);
    if (!job) return Promise.resolve(this.ctx.gone('job'));
    return this.mutate(job.agentId, 'remove job', ref => this.ctx.api.agents.cron.remove(ref, id));
  }

  byId(id: string): CronJob | null {
    return this.jobs().find(j => j.id === id) ?? null;
  }

  /** Drops the jobs of profiles or containers that no longer exist. */
  dropByContainer(containerId: string): void {
    this.jobs.update(js => js.filter(j => j.containerId !== containerId));
  }

  dropByAgent(agentId: string): void {
    this.jobs.update(js => js.filter(j => j.agentId !== agentId));
  }

  /**
   * Runs a mutation against the profile that owns the job and folds the schedule it
   * answered with back in, replacing only that profile's jobs — another profile's
   * schedule is not this answer's to speak for.
   */
  private async mutate(
    agentId: string, label: string, call: (ref: { hostId: string; containerId: string; name: string }) =>
      Promise<{ jobs: ApiCronJob[]; schedulerRunning: boolean }>,
  ): Promise<boolean> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) return this.ctx.gone('profile');
    try {
      const answer = await call(resolved.ref);
      const fresh = answer.jobs.map(job => toCronJob(job, resolved.agent.containerId, agentId));
      this.jobs.update(js => [...js.filter(j => j.agentId !== agentId), ...fresh]
        .sort((a, b) => a.nextRun - b.nextRun));
      this.schedulerRunning.set(answer.schedulerRunning);
      return true;
    } catch (e) {
      this.ctx.toastFailure(label, e);
      return false;
    }
  }
}

/** Only non-blank fields are sent, so an edit leaves everything else as it was. */
function request(fields: {
  schedule?: string; prompt?: string; name?: string; deliver?: string;
}): ApiCronJobRequest {
  const out: ApiCronJobRequest = {};
  if (fields.schedule?.trim()) out.schedule = fields.schedule.trim();
  if (fields.prompt?.trim()) out.prompt = fields.prompt.trim();
  if (fields.name?.trim()) out.name = fields.name.trim();
  if (fields.deliver?.trim()) out.deliver = fields.deliver.trim();
  return out;
}

/**
 * The page's shape. A job hermes has never run has no next run either — it is shown last,
 * which is what `Infinity` sorts to, rather than as due now.
 */
function toCronJob(job: ApiCronJob, containerId: string, agentId: string): CronJob {
  return {
    id: job.id,
    containerId,
    agentId,
    name: job.name || job.id,
    schedule: job.schedule ?? '',
    prompt: job.prompt ?? '',
    deliverTo: job.deliver ?? 'local',
    enabled: job.enabled,
    lastRun: job.lastRunAt,
    lastStatus: job.lastStatus === 'ok' || job.lastStatus === 'fail' ? job.lastStatus : null,
    nextRun: job.nextRunAt ?? Number.POSITIVE_INFINITY,
  };
}
