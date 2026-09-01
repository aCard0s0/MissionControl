import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { SkillGroup, SkillGroupInput } from '../models';
import { StoreContext } from './store-context';
import { toSkillGroup } from './wire-mappers';

/**
 * The skill groups — how the library is filed, and optionally which guide explains a set.
 *
 * The thinnest slice here, because a group has no deploy and nothing to poll: it is four
 * fields the operator maintains. Kept by name rather than newest-first, which the backend
 * already does — these are the headers the skills list is filed under, so re-sorting them on
 * an edit would move every skill beneath one.
 */
@Injectable({ providedIn: 'root' })
export class SkillGroupStore {
  readonly groups: WritableSignal<SkillGroup[]> = signal([]);

  private readonly ctx = inject(StoreContext);


  async refresh(): Promise<void> {
    try {
      this.groups.set((await this.ctx.api.skillGroups.list()).map(toSkillGroup));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: SkillGroupInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.skillGroups.update(id, input)
        : await this.ctx.api.skillGroups.create(input);
      this.upsert(toSkillGroup(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save skill group', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.skillGroups.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete skill group', e);
      return false;
    }
    this.groups.update(gs => gs.filter(g => g.id !== id));
    return true;
  }

  private upsert(group: SkillGroup): void {
    this.groups.update(gs => [
      ...gs.filter(g => g.id !== group.id), group,
    ].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
  }
}
