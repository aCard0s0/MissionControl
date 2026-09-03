import { computed, Injectable } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { ApiSkillGuide } from '../hermes-api';
import { DeployedPart, SkillGuide, SkillGuideInput } from '../models';
import { LibraryStore } from './library-store';
import { toDeployedPart, toSkillGuide } from './wire-mappers';

/**
 * The guide library — prose that composes several skills with the MCP servers they need.
 *
 * Deploy is the one call here that is not all-or-nothing: it answers with a row per part,
 * because a guide names things that can go missing between the writing and the deploying.
 * The store keeps that report rather than reducing it to a boolean, so the page can show
 * which half landed.
 */
@Injectable({ providedIn: 'root' })
export class SkillGuideStore extends LibraryStore<SkillGuide, ApiSkillGuide, SkillGuideInput> {
  readonly guides = this.items;

  readonly categories = computed(() =>
    [...new Set(this.guides().map(g => g.category))].sort());

  protected readonly noun = 'guide';
  protected readonly toModel = toSkillGuide;

  protected wire() {
    return this.ctx.api.guides;
  }

  /**
   * Puts a whole guide on one agent.
   *
   * Answers the per-part report, or null when the request itself failed. A guide that
   * half-landed still returns its report — the caller renders it rather than treating a
   * partial deploy as a plain success or a plain failure.
   */
  async deploy(id: string, agent: AgentRef): Promise<DeployedPart[] | null> {
    try {
      const result = await this.ctx.api.guides.deploy(id, agent);
      return (result.parts ?? []).map(toDeployedPart);
    } catch (e) {
      this.ctx.toastFailure('deploy guide', e);
      return null;
    }
  }
}
