import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { DeployedPart, SkillGuide, SkillGuideInput } from '../models';
import { StoreContext } from './store-context';
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
export class SkillGuideStore {
  readonly guides: WritableSignal<SkillGuide[]> = signal([]);

  readonly categories = computed(() =>
    [...new Set(this.guides().map(g => g.category))].sort());

  private readonly ctx = inject(StoreContext);

  byId = (id: string | null): SkillGuide | null =>
    this.guides().find(g => g.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.guides.set((await this.ctx.api.guides.list()).map(toSkillGuide));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: SkillGuideInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.guides.update(id, input)
        : await this.ctx.api.guides.create(input);
      this.upsert(toSkillGuide(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save guide', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.guides.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete guide', e);
      return false;
    }
    this.guides.update(gs => gs.filter(g => g.id !== id));
    return true;
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

  private upsert(guide: SkillGuide): void {
    this.guides.update(gs => [guide, ...gs.filter(g => g.id !== guide.id)]);
  }
}
