import { inject, signal, WritableSignal } from '@angular/core';
import { CrudApi } from '../hermes-api';
import { StoreContext } from './store-context';

/**
 * A dashboard-owned library: a list the operator maintains, read whole and written one row at
 * a time.
 *
 * Eight slices had `refresh` / `save` / `remove` / `upsert` written out over their own noun —
 * the same thirty-five lines, differing in an api client, a mapper, a word for the toast and
 * which of the two orders they keep. That is what lives here; everything a library does that
 * is its own — a deploy, an upstream check, a category list, a picker's filter — stays in the
 * slice, which is where a reader looks for it.
 *
 * Writes go to the backend first and are mirrored into the signal only once it agrees. A board
 * move is optimistic because a card snapping back is legible; a library row that looked saved
 * and was not would be found missing much later, so these wait.
 *
 * Which failures speak is the rule {@link StoreContext.toastFailure} states: an operator action
 * always says why it failed, a background read stays quiet and keeps its last known state.
 *
 * `McpCatalogStore` is deliberately not one of these. Its refresh carries a silent flag, its
 * save refuses a duplicate name and starts an operation poll, and its delete answers the entry
 * — three overrides of four methods, which is a base class buying nothing.
 */
export abstract class LibraryStore<T extends { id: string }, W extends { id: string }, I> {
  /**
   * Everything the library holds. Each slice re-exports this signal under its own noun —
   * `skills`, `groups`, `credentials` — because that is what reads at a call site; this is
   * the same signal, not a copy.
   */
  readonly items: WritableSignal<T[]> = signal([]);

  protected readonly ctx = inject(StoreContext);

  /** What a toast calls this: `save ${noun} failed: …`, `delete ${noun} failed: …`. */
  protected abstract readonly noun: string;

  /** Backend payload → domain model, from `wire-mappers`. */
  protected abstract readonly toModel: (row: W) => T;

  /**
   * The client this library reads and writes through.
   *
   * A method rather than a field: `ctx.api` is the seam a test substitutes, and it is replaced
   * after the slice is constructed, so a field would capture the real one and never see it.
   */
  protected abstract wire(): CrudApi<W, I>;

  /**
   * Where a saved row lands. Newest edit first, which is the order the backend lists these in
   * — an edit is the operator's most recent interest.
   *
   * The slices whose rows are headers or dropdown options take {@link byName} instead.
   */
  protected readonly order: (rest: T[], saved: T) => T[] = (rest, saved) => [saved, ...rest];

  byId = (id: string | null): T | null => this.items().find(item => item.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.items.set((await this.wire().list()).map(this.toModel));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: I, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.wire().update(id, input)
        : await this.wire().create(input);
      this.upsert(this.toModel(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure(`save ${this.noun}`, e);
      return '';
    }
  }

  /** Answers whether the row is gone, so a caller can close an editor open on it. */
  async remove(id: string): Promise<boolean> {
    try {
      await this.wire().remove(id);
    } catch (e) {
      this.ctx.toastFailure(`delete ${this.noun}`, e);
      return false;
    }
    this.items.update(list => list.filter(item => item.id !== id));
    return true;
  }

  /** Puts one row in place, replacing whatever the list held under its id. */
  protected upsert(item: T): void {
    this.items.update(list => this.order(list.filter(held => held.id !== item.id), item));
  }
}

/**
 * Kept by name, which is the order the backend reads them in.
 *
 * For the libraries whose rows are not a list to scan but a fixture of the page: the headers
 * another list is filed under, and the options in a picker. Re-sorting those on an edit would
 * move every row beneath a header, or move an option because something unrelated was renamed.
 */
export function byName<T extends { id: string; name: string }>(rest: T[], saved: T): T[] {
  return [...rest, saved]
    .sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' }));
}
