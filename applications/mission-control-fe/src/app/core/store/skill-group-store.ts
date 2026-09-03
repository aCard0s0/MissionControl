import { Injectable } from '@angular/core';
import { ApiSkillGroup } from '../hermes-api';
import { SkillGroup, SkillGroupInput } from '../models';
import { byName, LibraryStore } from './library-store';
import { toSkillGroup } from './wire-mappers';

/**
 * The skill groups — how the library is filed, and optionally which guide explains a set.
 *
 * The thinnest slice here, because a group has no deploy and nothing to poll: it is four
 * fields the operator maintains. Kept by name, because these are the headers the skills list
 * is filed under — see {@link byName}.
 */
@Injectable({ providedIn: 'root' })
export class SkillGroupStore extends LibraryStore<SkillGroup, ApiSkillGroup, SkillGroupInput> {
  readonly groups = this.items;

  protected readonly noun = 'skill group';
  protected readonly toModel = toSkillGroup;
  protected override readonly order = byName;

  protected wire() {
    return this.ctx.api.skillGroups;
  }
}
