import { describe, expect, it, vi } from 'vitest';
import { GroupDraft } from './filing';

/** A group as the three pages hand one to the editor. */
const group = { id: 'g-1', name: 'pdf', description: 'everything paper' };

/** The slice a draft saves through, answering the id a real one would. */
const storeAnswering = (id: string) => ({ save: vi.fn().mockResolvedValue(id) });

describe('GroupDraft', () => {
  it('opens blank for a new group, and on the row for an edit', () => {
    const draft = new GroupDraft();

    draft.begin();
    expect(draft.open()).toBe(true);
    expect(draft.editId()).toBeNull();
    expect([draft.name, draft.description, draft.ids()]).toEqual(['', '', []]);

    draft.begin(group, ['s-1', 's-2']);
    expect(draft.editId()).toBe('g-1');
    expect(draft.name).toBe('pdf');
    expect(draft.ids()).toEqual(['s-1', 's-2']);
  });

  it('copies the ids rather than holding the group\'s own array', () => {
    const ids = ['s-1'];
    const draft = new GroupDraft();

    draft.begin(group, ids);
    draft.toggle('s-2');

    // the editor must not edit the list the page is still rendering
    expect(ids).toEqual(['s-1']);
    expect(draft.ids()).toEqual(['s-1', 's-2']);
  });

  it('toggles a pick off again', () => {
    const draft = new GroupDraft();
    draft.begin(group, ['s-1']);

    draft.toggle('s-1');

    expect(draft.ids()).toEqual([]);
  });

  it('refuses to save without a name, or while a save is in flight', async () => {
    const draft = new GroupDraft();
    const store = storeAnswering('g-2');
    draft.begin();

    expect(draft.canSave()).toBe(false);
    await draft.save(store, f => f);
    expect(store.save).not.toHaveBeenCalled();

    draft.name = '   ';
    expect(draft.canSave()).toBe(false);

    draft.name = 'pdf';
    expect(draft.canSave()).toBe(true);
    draft.saving.set(true);
    expect(draft.canSave()).toBe(false);
  });

  it('saves the trimmed fields under the id it is editing, then closes', async () => {
    const draft = new GroupDraft();
    const store = storeAnswering('g-1');
    draft.begin(group, ['s-1']);
    draft.name = '  pdf tools  ';
    draft.description = '  paperwork  ';

    await draft.save(store, f => ({ name: f.name, description: f.description, skillIds: f.ids }));

    expect(store.save).toHaveBeenCalledWith(
      { name: 'pdf tools', description: 'paperwork', skillIds: ['s-1'] }, 'g-1');
    expect(draft.open()).toBe(false);
    expect(draft.saving()).toBe(false);
  });

  it('sends no id for a new group', async () => {
    const draft = new GroupDraft();
    const store = storeAnswering('g-3');
    draft.begin();
    draft.name = 'new';

    await draft.save(store, f => f);

    expect(store.save).toHaveBeenCalledWith(expect.anything(), undefined);
  });

  it('keeps the editor open with the picks in it when the save fails', async () => {
    const draft = new GroupDraft();
    const store = storeAnswering('');   // what a slice answers on failure
    draft.begin(group, ['s-1']);
    draft.name = 'pdf';

    await draft.save(store, f => f);

    // retyping a group because the backend blinked is what this must never cost
    expect(draft.open()).toBe(true);
    expect(draft.ids()).toEqual(['s-1']);
    expect(draft.saving()).toBe(false);
  });

  it('closes on a delete of the group it is editing, and only that one', () => {
    const draft = new GroupDraft();
    draft.begin(group, []);

    draft.closeIf('g-2');
    expect(draft.open()).toBe(true);

    draft.closeIf('g-1');
    expect(draft.open()).toBe(false);
    expect(draft.editId()).toBeNull();
  });
});
