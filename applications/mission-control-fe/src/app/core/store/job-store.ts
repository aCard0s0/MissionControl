import { WritableSignal, computed, signal } from '@angular/core';
import { CronJob } from '../models';
import { seedJobs } from '../mock-data';
import { ContainerStore } from './container-store';
import { StoreContext, nid } from './store-context';

/** Scheduled prompts per container. Still mock-only: the live backend has no
 *  scheduling endpoints yet, so creating one toasts instead of pretending. */
export class JobStore {
  readonly jobs: WritableSignal<CronJob[]>;

  readonly forSelectedContainer = computed(() =>
    this.jobs().filter(j => j.containerId === this.containers.selectedContainerId()));

  constructor(private readonly ctx: StoreContext, private readonly containers: ContainerStore) {
    this.jobs = signal(ctx.mock ? seedJobs() : []);
  }

  toggle(id: string): void {
    this.jobs.update(js => js.map(j => j.id === id ? { ...j, enabled: !j.enabled } : j));
  }

  update(id: string, patch: Partial<CronJob>): void {
    this.jobs.update(js => js.map(j => j.id === id ? { ...j, ...patch } : j));
  }

  create(
    containerId: string, agentId: string, name: string, schedule: string,
    prompt: string, deliverTo: string,
  ): void {
    if (!this.ctx.mock) {
      this.ctx.toast('scheduling requires the hermes adapter — not available in live mode yet');
      return;
    }
    this.jobs.update(js => [...js, {
      id: nid('j'), containerId, agentId, name, schedule, prompt, deliverTo,
      enabled: true, lastRun: null, lastStatus: null,
      nextRun: Date.now() + 3_600_000,
    }]);
  }

  remove(id: string): void {
    this.jobs.update(js => js.filter(j => j.id !== id));
  }

  /** Drops the jobs of containers or agents that no longer exist. */
  dropByContainer(containerId: string): void {
    this.jobs.update(js => js.filter(j => j.containerId !== containerId));
  }

  dropByAgent(agentId: string): void {
    this.jobs.update(js => js.filter(j => j.agentId !== agentId));
  }

  reassignContainer(fromId: string, toId: string): void {
    this.jobs.update(js => js.map(j => j.containerId === fromId ? { ...j, containerId: toId } : j));
  }
}
