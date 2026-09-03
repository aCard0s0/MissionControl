import { signal } from '@angular/core';

/**
 * Filing a library list under the groups that claim it, and the editor that does the filing.
 *
 * Three pages do this the same way — Skills, Prompts and, for its picker, MCP Servers — over
 * three unrelated group types whose id lists are named differently. The accessor is what makes
 * one function serve all three; nothing else about them is shared.
 */

/** The section key for rows no group claims. Not an id, so it cannot collide with one. */
export const UNGROUPED = '';

/** One band of a filed list: a group and the visible rows it claims, or the trailing bucket. */
export interface FiledSection<T, G> {
  readonly key: string;
  readonly group: G | null;
  readonly items: T[];
}

/**
 * The list as it is filed: one section per group, then whatever no group claims.
 *
 * A row appears under **every** group that names it. The ids live on the group, so nothing
 * stops two groups claiming one row, and showing it once would mean silently picking a winner —
 * the group editors mark a row another group already holds instead, so double-filing is visible
 * where it is done.
 *
 * `filtering` decides what an empty group does. Unfiltered it keeps its header, because a group
 * you just made has to be visible to be filled; under a search it disappears, because a header
 * with no rows under it reads as a match that is not there.
 *
 * With no groups at all this collapses to a single unlabelled section — the flat list these
 * pages had before.
 */
export function fileIntoSections<T extends { id: string }, G>(
  items: readonly T[],
  groups: readonly G[],
  idsOf: (group: G) => readonly string[],
  filtering: boolean,
  keyOf: (group: G) => string,
): FiledSection<T, G>[] {
  if (!groups.length) {
    return [{ key: UNGROUPED, group: null, items: [...items] }];
  }
  const byId = new Map(items.map(item => [item.id, item]));
  const filed = new Set<string>();
  const sections: FiledSection<T, G>[] = [];
  for (const group of groups) {
    const members: T[] = [];
    for (const id of idsOf(group)) {
      const item = byId.get(id);
      if (item) {
        members.push(item);
        filed.add(id);
      }
    }
    if (members.length || !filtering) {
      sections.push({ key: keyOf(group), group, items: members });
    }
  }
  const rest = items.filter(item => !filed.has(item.id));
  if (rest.length) {
    sections.push({ key: UNGROUPED, group: null, items: rest });
  }
  return sections;
}

/**
 * The name of another group already holding this row, or ''.
 *
 * Shown in a group editor so double-filing is visible at the moment it is done — deliberate on
 * the MCP page, where one server often belongs to several sets, and usually a mistake on the
 * other two.
 */
export function groupHolding<G extends { id: string; name: string }>(
  groups: readonly G[],
  idsOf: (group: G) => readonly string[],
  itemId: string,
  exceptId: string | null,
): string {
  return groups.find(g => g.id !== exceptId && idsOf(g).includes(itemId))?.name ?? '';
}

/** What every group editor opens on: the three fields all three group types share. */
export interface GroupHeader {
  readonly id: string;
  readonly name: string;
  readonly description: string;
}

/**
 * The group editor's state, which the three pages each kept a copy of: open, editing which,
 * saving, a name, a description and the ids picked.
 *
 * Not a component. The three editors look nothing alike — one picks a guide as well, one sits
 * in a tab, the chips carry different warnings — so what they share is the state behind the
 * markup, not the markup. A page holds one of these and binds to it.
 *
 * The id list is the reason `save` takes a builder: the three name it after what it holds
 * (`skillIds`, `promptIds`, `serverIds`), and a skill group carries a guide beside it.
 */
export class GroupDraft {
  /** Whether the editor is showing. */
  readonly open = signal(false);

  /** The group being edited, or null while composing a new one. */
  readonly editId = signal<string | null>(null);

  readonly saving = signal(false);

  // Plain fields, not signals: the editor's template writes them through `ngModel`, and that
  // event is what re-evaluates `canSave()`.
  name = '';
  description = '';

  /** A signal, unlike the two fields above: chips write it, not `ngModel`. */
  readonly ids = signal<string[]>([]);

  /** Opens the editor — on an existing group with the ids it claims, or blank for a new one. */
  begin(group: GroupHeader | null = null, ids: readonly string[] = []): void {
    this.editId.set(group?.id ?? null);
    this.name = group?.name ?? '';
    this.description = group?.description ?? '';
    this.ids.set([...ids]);
    this.open.set(true);
  }

  close(): void {
    this.open.set(false);
    this.editId.set(null);
  }

  /** Closes the editor only if it is open on `id` — what a delete of that group needs. */
  closeIf(id: string): void {
    if (this.editId() === id) this.close();
  }

  toggle(id: string): void {
    this.ids.update(ids => ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id]);
  }

  canSave(): boolean {
    return !this.saving() && !!this.name.trim();
  }

  /**
   * Saves through the slice that owns this group type, and closes only if it landed: a failed
   * save keeps the editor open with the picks still in it.
   */
  async save<I>(
    store: { save(input: I, id?: string): Promise<string> },
    input: (fields: { name: string; description: string; ids: string[] }) => I,
  ): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    const saved = await store.save(
      input({ name: this.name.trim(), description: this.description.trim(), ids: this.ids() }),
      this.editId() ?? undefined,
    );
    this.saving.set(false);
    if (saved) this.close();
  }
}
