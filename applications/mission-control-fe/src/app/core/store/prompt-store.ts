import { computed, Injectable } from '@angular/core';
import { ApiPrompt } from '../hermes-api';
import { Prompt, PromptInput } from '../models';
import { LibraryStore } from './library-store';
import { toPrompt } from './wire-mappers';

/**
 * The prompt library — global, like blueprints and unlike the board: a prompt is text,
 * so it belongs to the operator rather than to one container.
 */
@Injectable({ providedIn: 'root' })
export class PromptStore extends LibraryStore<Prompt, ApiPrompt, PromptInput> {
  readonly prompts = this.items;

  /** Every category currently in use — what the page's filter chips are built from. */
  readonly categories = computed(() =>
    [...new Set(this.prompts().map(p => p.category))].sort());

  protected readonly noun = 'prompt';
  protected readonly toModel = toPrompt;

  protected wire() {
    return this.ctx.api.prompts;
  }
}
