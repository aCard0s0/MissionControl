import { Injectable } from '@angular/core';
import { ApiPromptGroup } from '../hermes-api';
import { PromptGroup, PromptGroupInput } from '../models';
import { byName, LibraryStore } from './library-store';
import { toPromptGroup } from './wire-mappers';

/**
 * The prompt groups — how the prompt library is filed.
 *
 * The twin of {@link SkillGroupStore}, kept separate for the reason its backend record gives:
 * the two hold ids from different tables, and one store over both would have to be told which
 * every time it was asked anything.
 */
@Injectable({ providedIn: 'root' })
export class PromptGroupStore
  extends LibraryStore<PromptGroup, ApiPromptGroup, PromptGroupInput> {
  readonly groups = this.items;

  protected readonly noun = 'prompt group';
  protected readonly toModel = toPromptGroup;
  protected override readonly order = byName;

  protected wire() {
    return this.ctx.api.promptGroups;
  }
}
