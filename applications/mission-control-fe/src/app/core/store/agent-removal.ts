import { inject, Injectable } from '@angular/core';
import { AgentSetupStore } from './agent-setup-store';
import { AgentStore } from './agent-store';
import { BoardStore } from './board-store';
import { JobStore } from './job-store';
import { WebhookStore } from './webhook-store';

/**
 * Removing a profile, and forgetting everything that was keyed to it: its
 * scheduled jobs, its board tasks, its webhook routes and listener, and the
 * cached `.env` its Setup tab was rendering.
 *
 * This is the one rule in the store layer that spans slices, so it is a
 * collaborator of its own rather than a callback the caller has to remember to
 * pass. Nothing else drops those rows on a profile's behalf: leaving them behind
 * would show an operator a schedule for a profile that no longer exists, and the
 * credentials cache is the one that actually matters — it holds what the backend
 * just deleted.
 */
@Injectable({ providedIn: 'root' })
export class AgentRemoval {
  private readonly agents = inject(AgentStore);
  private readonly jobs = inject(JobStore);
  private readonly board = inject(BoardStore);
  private readonly webhooks = inject(WebhookStore);
  private readonly setup = inject(AgentSetupStore);

  /** Removes the profile, then forgets what was keyed to it. Answers false when
   *  the backend refused, in which case nothing local is dropped. */
  async remove(agentId: string): Promise<boolean> {
    if (!await this.agents.remove(agentId)) return false;
    this.jobs.dropByAgent(agentId);
    this.board.dropByAgent(agentId);
    this.webhooks.dropByAgent(agentId);
    this.setup.forget(agentId);
    return true;
  }
}
