import { WritableSignal, computed, signal } from '@angular/core';
import { CronJob } from '../models';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';

/** Scheduled prompts per container. There is no scheduling endpoint yet, so this
 *  holds nothing and creating one says so rather than pretending. */
export class JobStore {
  readonly jobs: WritableSignal<CronJob[]>;

  readonly forSelectedContainer = computed(() =>
    this.jobs().filter(j => j.containerId === this.containers.selectedContainerId()));

  constructor(private readonly ctx: StoreContext, private readonly containers: ContainerStore) {
    this.jobs = signal([]);
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
    this.ctx.toast('scheduling requires the hermes adapter — not available yet');
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
