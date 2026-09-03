import { computed, Injectable, WritableSignal, signal } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { ApiSkill } from '../hermes-api';
import { Skill, SkillInput, Upstream } from '../models';
import { LibraryStore } from './library-store';
import { toSkill, toUpstream } from './wire-mappers';

/**
 * The skill library — global, like prompts and blueprints: a library skill belongs to the
 * operator, and may not be on any agent at all.
 *
 * Distinct from `AgentSkillStore`, which toggles and edits the skills one profile already
 * has. Nothing here reads or writes an agent except `deploy` and `importFrom`, which are
 * the two crossings between the library and a container.
 */
@Injectable({ providedIn: 'root' })
export class SkillStore extends LibraryStore<Skill, ApiSkill, SkillInput> {
  readonly skills = this.items;

  /** Every category currently in use — what the page's filter chips are built from. */
  readonly categories = computed(() =>
    [...new Set(this.skills().map(s => s.category))].sort());

  /**
   * The last repository check per skill id, keyed rather than stored on the row: it is not
   * part of what the library holds, it reaches the network, and it is only ever asked for
   * one skill at a time.
   */
  readonly upstream: WritableSignal<Record<string, Upstream>> = signal({});

  protected readonly noun = 'skill';
  protected readonly toModel = toSkill;

  protected wire() {
    return this.ctx.api.skills;
  }

  /** Puts a library skill on one agent. Answers whether it landed. */
  async deploy(id: string, agent: AgentRef): Promise<boolean> {
    try {
      await this.ctx.api.skills.deploy(id, agent);
      return true;
    } catch (e) {
      this.ctx.toastFailure('deploy skill', e);
      return false;
    }
  }

  /** Copies a skill off an agent into the library. Says so when files were left behind:
   *  a partial import that reported success would be found broken on the next deploy. */
  async importFrom(agent: AgentRef, skillName: string): Promise<boolean> {
    try {
      const imported = await this.ctx.api.skills.importFrom(agent, skillName);
      this.upsert(toSkill(imported.skill));
      const skipped = imported.skipped ?? [];
      this.ctx.notify(skipped.length
        ? `saved ${skillName} to the library without ${skipped.length} non-text `
          + `file${skipped.length === 1 ? '' : 's'}: ${skipped.join(', ')}`
        : `saved ${skillName} to the library`);
      return true;
    } catch (e) {
      this.ctx.toastFailure('import skill', e);
      return false;
    }
  }

  /** Asks whether this skill's repository has moved on. Never throws: an unreachable
   *  github is reported in the row rather than as a toast, because it is a fact about the
   *  skill and not a failed operation. */
  async checkUpstream(id: string): Promise<void> {
    this.setUpstream(id, { status: 'checking', latest: '', detail: '', checkedAt: null });
    try {
      this.setUpstream(id, toUpstream(await this.ctx.api.skills.upstream(id)));
    } catch {
      this.setUpstream(id, {
        status: 'unavailable', latest: '', detail: 'the check could not be made', checkedAt: null,
      });
    }
  }

  private setUpstream(id: string, state: Upstream): void {
    this.upstream.update(all => ({ ...all, [id]: state }));
  }
}
