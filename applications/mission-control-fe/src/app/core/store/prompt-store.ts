import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { Prompt, PromptInput } from '../models';
import { StoreContext } from './store-context';
import { toPrompt } from './wire-mappers';

/**
 * The prompt library — global, like blueprints and unlike the board: a prompt is text,
 * so it belongs to the operator rather than to one container.
 *
 * Writes go to the backend first and are only mirrored into the signal once it agrees.
 * A board move is optimistic because a card snapping back is legible; a prompt that
 * looked saved and was not would be found missing much later, so this one waits.
 */
@Injectable({ providedIn: 'root' })
export class PromptStore {
  readonly prompts: WritableSignal<Prompt[]>;

  /** Every category currently in use — what the page's filter chips are built from. */
  readonly categories = computed(() =>
    [...new Set(this.prompts().map(p => p.category))].sort());

  private readonly ctx = inject(StoreContext);

  constructor() {
    this.prompts = signal([]);
  }

  byId = (id: string | null): Prompt | null =>
    this.prompts().find(p => p.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.prompts.set((await this.ctx.api.prompts.list()).map(toPrompt));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id) a prompt. Returns the id, or '' on failure. */
  async save(input: PromptInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.prompts.update(id, input)
        : await this.ctx.api.prompts.create(input);
      this.upsert(toPrompt(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save prompt', e);
      return '';
    }
  }

  /** Answers whether the prompt is gone, so a caller can close an editor open on it. */
  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.prompts.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete prompt', e);
      return false;
    }
    this.prompts.update(ps => ps.filter(p => p.id !== id));
    return true;
  }

  /** Newest edit first, which is the order the backend lists in. */
  private upsert(prompt: Prompt): void {
    this.prompts.update(ps => [prompt, ...ps.filter(p => p.id !== prompt.id)]);
  }
}
