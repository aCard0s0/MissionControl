import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { Skill, SkillInput } from '../models';
import { StoreContext } from './store-context';
import { toSkill } from './wire-mappers';

/**
 * The skill library — global, like prompts and blueprints: a library skill belongs to the
 * operator, and may not be on any agent at all.
 *
 * Distinct from `AgentSkillStore`, which toggles and edits the skills one profile already
 * has. Nothing here reads or writes an agent except `deploy` and `importFrom`, which are
 * the two crossings between the library and a container.
 *
 * Writes go to the backend first and are mirrored into the signal only once it agrees,
 * for the reason `PromptStore` gives: a skill that looked saved and was not would be
 * found missing much later.
 */
@Injectable({ providedIn: 'root' })
export class SkillStore {
  readonly skills: WritableSignal<Skill[]> = signal([]);

  /** Every category currently in use — what the page's filter chips are built from. */
  readonly categories = computed(() =>
    [...new Set(this.skills().map(s => s.category))].sort());

  private readonly ctx = inject(StoreContext);

  byId = (id: string | null): Skill | null =>
    this.skills().find(s => s.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.skills.set((await this.ctx.api.skills.list()).map(toSkill));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id) a library skill. Returns the id, or '' on failure. */
  async save(input: SkillInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.skills.update(id, input)
        : await this.ctx.api.skills.create(input);
      this.upsert(toSkill(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save skill', e);
      return '';
    }
  }

  /** Answers whether the skill is gone, so a caller can close an editor open on it.
   *  Removes the library row only — any copy already on an agent stays there. */
  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.skills.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete skill', e);
      return false;
    }
    this.skills.update(ss => ss.filter(s => s.id !== id));
    return true;
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

  /** Newest edit first, which is the order the backend lists in. */
  private upsert(skill: Skill): void {
    this.skills.update(ss => [skill, ...ss.filter(s => s.id !== skill.id)]);
  }
}
