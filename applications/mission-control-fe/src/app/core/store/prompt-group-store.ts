import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { PromptGroup, PromptGroupInput } from '../models';
import { StoreContext } from './store-context';
import { toPromptGroup } from './wire-mappers';

/**
 * The prompt groups — how the prompt library is filed.
 *
 * The twin of {@link SkillGroupStore}, kept separate for the reason its backend record gives:
 * the two hold ids from different tables, and one store over both would have to be told which
 * every time it was asked anything.
 */
@Injectable({ providedIn: 'root' })
export class PromptGroupStore {
  readonly groups: WritableSignal<PromptGroup[]> = signal([]);

  private readonly ctx = inject(StoreContext);


  async refresh(): Promise<void> {
    try {
      this.groups.set((await this.ctx.api.promptGroups.list()).map(toPromptGroup));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: PromptGroupInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.promptGroups.update(id, input)
        : await this.ctx.api.promptGroups.create(input);
      this.upsert(toPromptGroup(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save prompt group', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.promptGroups.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete prompt group', e);
      return false;
    }
    this.groups.update(gs => gs.filter(g => g.id !== id));
    return true;
  }

  /** Kept by name, which is the order the backend reads them in: these are the headers the
   *  prompt list is filed under, so a save must not move them. */
  private upsert(group: PromptGroup): void {
    this.groups.update(gs => [
      ...gs.filter(g => g.id !== group.id), group,
    ].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
  }
}
