import { signal } from '@angular/core';

/**
 * One model field plus the suggestions behind it. The field is free text backed
 * by a `<datalist>`, because a provider without a catalog takes any model id the
 * operator types; the suggestions only auto-select when a catalog answered.
 *
 * Every load carries a monotonic sequence number, so a slow catalog read for a
 * provider the operator has since switched away from cannot land on the new one.
 * A dialog offering two pickers — a main model and an auxiliary override — gives
 * each its own instance, so loading one never cancels the other.
 *
 * Plain mutable `model`, because the template binds it with `[(ngModel)]`.
 */
export class ModelPicker {
  model = '';

  readonly suggestions = signal<string[]>([]);
  readonly loading = signal(false);

  private seq = 0;

  /**
   * Replaces the suggestion list from `fetch`. A `preferred` model (e.g. the one
   * a template names) wins over the list, but still respects the sequence guard
   * so a superseded load cannot apply it. On failure the list falls back to
   * `keepOnError` — empty for a provider switch, where the previous provider's
   * models would be wrong, and the current list for a plain refresh.
   */
  async load(
    fetch: Promise<string[]>,
    opts: { preferred?: string; keepOnError?: boolean } = {},
  ): Promise<void> {
    const seq = ++this.seq;
    this.loading.set(true);
    let list: string[];
    try {
      list = await fetch;
    } catch {
      list = opts.keepOnError ? this.suggestions() : [];
    }
    if (seq !== this.seq) return;   // a newer load superseded this one
    this.suggestions.set(list);
    this.loading.set(false);
    if (opts.preferred) this.model = opts.preferred;
    else if (list.length && !list.includes(this.model)) this.model = list[0];
  }

  /** Drops the selection and the suggestions, and abandons any load in flight. */
  reset(): void {
    this.seq++;
    this.model = '';
    this.suggestions.set([]);
    this.loading.set(false);
  }
}
