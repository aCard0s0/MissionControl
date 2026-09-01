/**
 * Filing a library list under the groups that claim it.
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
