import { computed, signal } from '@angular/core';
import { ModelCatalog, ModelSource } from '../core/models';

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

  /** Where the current suggestions came from; null before the first load. */
  readonly source = signal<ModelSource | null>(null);

  /** The provenance as a field hint, or null when there is nothing worth saying — an empty
   *  list has no source to report, a failed read has none either, and `live` means the
   *  operator's own key just answered, which they already know because they typed it. */
  readonly sourceLabel = computed(() => {
    if (!this.suggestions().length) return null;
    switch (this.source()) {
      case 'catalog': return 'from the provider';
      case 'config': return 'shipped list';
      default: return null;
    }
  });

  private seq = 0;

  /**
   * Replaces the suggestion list from `fetch`. A `preferred` model (e.g. the one
   * a template names) wins over the list, but still respects the sequence guard
   * so a superseded load cannot apply it. On failure the list falls back to
   * `keepOnError` — empty for a provider switch, where the previous provider's
   * models would be wrong, and the current list for a plain refresh.
   */
  async load(
    fetch: Promise<ModelCatalog>,
    opts: { preferred?: string; keepOnError?: boolean } = {},
  ): Promise<void> {
    const seq = ++this.seq;
    this.loading.set(true);
    let list: string[];
    let source: ModelSource | null;
    try {
      ({ models: list, source } = await fetch);
    } catch {
      // keepOnError holds the current list, so it keeps the label that described it
      list = opts.keepOnError ? this.suggestions() : [];
      source = opts.keepOnError ? this.source() : null;
    }
    if (seq !== this.seq) return;   // a newer load superseded this one
    this.suggestions.set(list);
    this.source.set(source);
    this.loading.set(false);
    if (opts.preferred) this.model = opts.preferred;
    else if (list.length && !list.includes(this.model)) this.model = list[0];
  }

  /** Drops the selection and the suggestions, and abandons any load in flight. */
  reset(): void {
    this.seq++;
    this.model = '';
    this.suggestions.set([]);
    this.source.set(null);
    this.loading.set(false);
  }
}
