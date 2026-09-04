import '@angular/compiler';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PromptGroupStore } from '../core/store/prompt-group-store';
import { PromptStore } from '../core/store/prompt-store';
import { Prompt, PromptGroup } from '../core/models';
import { PromptsPage, splitTags } from './prompts';
import { button, buttonWith, el, fill, press, settle, text, stubConfirm } from '../testing/dom';

const prompt = (id: string, patch: Partial<Prompt> = {}): Prompt => ({
  id, title: `prompt ${id}`, body: `body of ${id}`, category: 'ops',
  notes: `notes of ${id}`, tags: ['ops'], createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const storeStub = (prompts: Prompt[] = [prompt('p-1')]) => {
  const list = signal(prompts);
  return {
    prompts: list,
    categories: signal([...new Set(prompts.map(p => p.category))].sort()),
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('p-new'),
    remove: vi.fn().mockResolvedValue(true),
    byId: (id: string) => list().find(p => p.id === id) ?? null,
  };
};

const group = (id: string, patch: Partial<PromptGroup> = {}): PromptGroup => ({
  id, name: `group-${id}`, description: '', promptIds: [],
  createdAt: 1_000, updatedAt: 2_000, ...patch,
});

const groupStub = (groups: PromptGroup[] = []) => {
  const list = signal(groups);
  return {
    groups: list,
    refresh: vi.fn().mockResolvedValue(undefined),
    save: vi.fn().mockResolvedValue('pg-new'),
    remove: vi.fn().mockResolvedValue(true),
    byId: (id: string) => list().find(g => g.id === id) ?? null,
  };
};

const render = (
  store: ReturnType<typeof storeStub> = storeStub(),
  groups: ReturnType<typeof groupStub> = groupStub(),
) => {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      { provide: PromptStore, useValue: store },
      { provide: PromptGroupStore, useValue: groups },
    ],
  });
  const fixture = TestBed.createComponent(PromptsPage);
  fixture.detectChanges();
  return { fixture, store, groups };
};

/** The row for one prompt, found by its title the way an operator would. */
const row = (fixture: { nativeElement: unknown }, title: string): HTMLElement => {
  const match = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.prompt'))
    .find(p => (p.querySelector('.title')?.textContent ?? '').trim() === title);
  if (!match) throw new Error(`no prompt row titled "${title}"`);
  return match;
};

describe('PromptsPage', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => localStorage.clear());

  // ── groups ───────────────────────────────────────────────────────────────

  it('leaves the list flat and unlabelled when no group exists', () => {
    const { fixture } = render(storeStub([prompt('p-1', { title: 'Triage' })]));

    expect(el(fixture).querySelector('.group-head')).toBeNull();
    expect(text(fixture)).toContain('Triage');
  });

  it('files each prompt under the group that names it', async () => {
    const { fixture } = render(
      storeStub([prompt('p-1', { title: 'Triage' }), prompt('p-2', { title: 'Rollback' })]),
      groupStub([group('pg-1', { name: 'incidents', promptIds: ['p-1'] })]));
    await settle(fixture);

    const heads = Array.from(el(fixture).querySelectorAll<HTMLElement>('.group-head'))
      .map(h => (h.querySelector('.g-name')?.textContent ?? '').trim());
    expect(heads).toEqual(['incidents', 'ungrouped']);
  });

  it('lists a prompt under every group that claims it, rather than picking a winner', async () => {
    const { fixture } = render(
      storeStub([prompt('p-1', { title: 'Triage' })]),
      groupStub([
        group('pg-1', { name: 'incidents', promptIds: ['p-1'] }),
        group('pg-2', { name: 'runbooks', promptIds: ['p-1'] }),
      ]));
    await settle(fixture);

    expect(el(fixture).querySelectorAll('.prompt').length).toBe(2);
    expect(el(fixture).querySelector('.group-head.loose')).toBeNull();
  });

  it('keeps an empty group visible until a filter is on', async () => {
    const { fixture } = render(
      storeStub([prompt('p-1', { title: 'Triage', category: 'ops' })]),
      groupStub([group('pg-1', { name: 'incidents', promptIds: [] })]));
    await settle(fixture);

    expect(text(fixture)).toContain('incidents');

    const search = el(fixture).querySelector<HTMLInputElement>('.input.find')!;
    search.value = 'Triage';
    search.dispatchEvent(new Event('input'));
    await settle(fixture);

    expect(text(fixture)).not.toContain('incidents');
  });

  it('sends the group the editor composed', async () => {
    const { fixture, groups } = render(storeStub([prompt('p-1', { title: 'Triage' })]));

    press(fixture, '+ new group');
    await settle(fixture);
    await fill(fixture, 'name', 'incidents');
    press(fixture, 'Triage', '.picker');
    await settle(fixture);
    press(fixture, 'save group');
    await settle(fixture);

    expect(groups.save).toHaveBeenCalledWith(
      { name: 'incidents', description: '', promptIds: ['p-1'] }, undefined);
  });

  it('will not save a group with no name', async () => {
    const { fixture, groups } = render();

    press(fixture, '+ new group');
    await settle(fixture);

    expect(buttonWith(fixture, 'save group').disabled).toBe(true);
    expect(groups.save).not.toHaveBeenCalled();
  });

  it('warns in the picker when another group already holds a prompt', async () => {
    const { fixture } = render(
      storeStub([prompt('p-1', { title: 'Triage' })]),
      groupStub([group('pg-1', { name: 'incidents', promptIds: ['p-1'] })]));

    press(fixture, '+ new group');
    await settle(fixture);

    const chip = button(fixture, 'Triage', '.picker');
    expect(chip.classList).toContain('warn');
    expect(chip.title).toContain('already in the group incidents');
  });

  it('says a group delete leaves its prompts alone, and confirms first', async () => {
    const { fixture, groups } = render(
      storeStub([prompt('p-1')]), groupStub([group('pg-1', { promptIds: ['p-1'] })]));
    await settle(fixture);
    const confirmed = stubConfirm(false);

    press(fixture, 'delete', '.group-head');
    await settle(fixture);

    expect(confirmed.mock.calls[0][0].message).toContain('only the filing goes');
    expect(groups.remove).not.toHaveBeenCalled();
  });

  it('loads a group into the editor with its prompts', async () => {
    const { fixture } = render(
      storeStub([prompt('p-1', { title: 'Triage' })]),
      groupStub([group('pg-1', {
        name: 'incidents', description: 'when it breaks', promptIds: ['p-1'],
      })]));
    await settle(fixture);

    press(fixture, 'edit', '.group-head');
    await settle(fixture);

    expect(el(fixture).querySelector<HTMLInputElement>('#group-name')!.value).toBe('incidents');
    expect(button(fixture, 'Triage', '.picker').classList).toContain('on');
  });

  it('reads the library when it opens, so a deep link is not an empty page', () => {
    const { store } = render();

    expect(store.refresh).toHaveBeenCalled();
  });

  it('lists a prompt with what it is filed under', () => {
    const { fixture } = render(storeStub([prompt('p-1', { title: 'Triage', tags: ['ops', 'triage'] })]));

    const card = row(fixture, 'Triage');
    expect(card.textContent).toContain('ops');
    expect(card.textContent).toContain('triage');
  });

  it('says so when the library is empty, and offers the one thing to do about it', () => {
    const { fixture } = render(storeStub([]));

    expect(text(fixture)).toContain('The library is empty');
    expect(button(fixture, '+ create one')).toBeTruthy();
  });

  // ── compact vs expanded ──────────────────────────────────────────────────
  it('opens compact: a one-line preview instead of the body', () => {
    const { fixture } = render(storeStub([prompt('p-1', { body: 'line one\nline two' })]));

    expect(el(fixture).querySelector('.prompt .body')).toBeNull();
    expect(text(fixture)).toContain('line one line two');
  });

  it('shows every body in the expanded view, notes included', () => {
    const { fixture } = render();

    press(fixture, 'expanded');

    expect(el(fixture).querySelector('.prompt .body')?.textContent).toContain('body of p-1');
    expect(text(fixture)).toContain('notes of p-1');
  });

  it('remembers the view, because it is a preference and not a mode', () => {
    const first = render();
    press(first.fixture, 'expanded');
    expect(localStorage.getItem('mc-prompt-view')).toBe('expanded');

    const second = render();

    expect(second.fixture.nativeElement.querySelector('.prompt .body')).toBeTruthy();
  });

  it('opens one prompt on its own in the compact view, and leaves the rest shut', () => {
    const { fixture } = render(storeStub([prompt('p-1'), prompt('p-2')]));

    row(fixture, 'prompt p-1').querySelector<HTMLButtonElement>('.disc')!.click();
    fixture.detectChanges();

    expect(row(fixture, 'prompt p-1').querySelector('.body')?.textContent).toContain('body of p-1');
    expect(row(fixture, 'prompt p-2').querySelector('.body')).toBeNull();
  });

  it('shuts one prompt on its own in the expanded view', () => {
    const { fixture } = render(storeStub([prompt('p-1'), prompt('p-2')]));
    press(fixture, 'expanded');

    row(fixture, 'prompt p-1').querySelector<HTMLButtonElement>('.disc')!.click();
    fixture.detectChanges();

    // the same set of exceptions reads the other way round once the view flips
    expect(row(fixture, 'prompt p-1').querySelector('.body')).toBeNull();
    expect(row(fixture, 'prompt p-2').querySelector('.body')).toBeTruthy();
  });

  it('forgets the per-row exceptions when the view changes', () => {
    const { fixture } = render(storeStub([prompt('p-1'), prompt('p-2')]));
    row(fixture, 'prompt p-1').querySelector<HTMLButtonElement>('.disc')!.click();
    fixture.detectChanges();

    press(fixture, 'expanded');

    // without the reset, p-1's "open" exception would read as "closed" here
    expect(row(fixture, 'prompt p-1').querySelector('.body')).toBeTruthy();
    expect(row(fixture, 'prompt p-2').querySelector('.body')).toBeTruthy();
  });

  // ── finding one again ────────────────────────────────────────────────────
  it('searches the body and the notes, not only the title', async () => {
    const { fixture } = render(storeStub([
      prompt('p-1', { title: 'Triage', body: 'quote the first error', notes: '' }),
      prompt('p-2', { title: 'Review', body: 'summarize the diff', notes: 'before merging' }),
    ]));

    await type(fixture, 'first error');
    expect(text(fixture)).toContain('1/2 prompts');
    expect(text(fixture)).toContain('Triage');
    expect(text(fixture)).not.toContain('Review');

    await type(fixture, 'before merging');
    expect(text(fixture)).toContain('Review');
    expect(text(fixture)).not.toContain('Triage');
  });

  it('searches tags, which is what a dictionary is filed by', async () => {
    const { fixture } = render(storeStub([
      prompt('p-1', { title: 'Triage', tags: ['incident'] }),
      prompt('p-2', { title: 'Review', tags: ['merge'] }),
    ]));

    await type(fixture, 'incident');

    expect(text(fixture)).toContain('Triage');
    expect(text(fixture)).not.toContain('Review');
  });

  it('narrows to one category, and back to the whole library', () => {
    const { fixture } = render(storeStub([
      prompt('p-1', { title: 'Triage', category: 'ops' }),
      prompt('p-2', { title: 'Review', category: 'review' }),
    ]));

    press(fixture, 'review');
    expect(text(fixture)).toContain('1/2 prompts');
    expect(text(fixture)).not.toContain('Triage');

    press(fixture, 'all');
    expect(text(fixture)).toContain('2/2 prompts');
  });

  it('offers a way back when a search matches nothing', async () => {
    const { fixture } = render(storeStub([prompt('p-1', { title: 'Triage' })]));

    await type(fixture, 'nothing like this');
    expect(text(fixture)).toContain('No prompt matches this search');

    press(fixture, 'clear filters');

    expect(text(fixture)).toContain('Triage');
  });

  // ── editing ──────────────────────────────────────────────────────────────
  it('refuses to save a prompt with no title or no body', async () => {
    const { fixture, store } = render();

    press(fixture, '+ new prompt');
    expect(button(fixture, 'save prompt').disabled).toBe(true);

    await fill(fixture, 'title', 'Triage');
    expect(button(fixture, 'save prompt').disabled).toBe(true);

    await fill(fixture, 'prompt', 'quote the first error');
    expect(button(fixture, 'save prompt').disabled).toBe(false);
    expect(store.save).not.toHaveBeenCalled();
  });

  it('saves a new prompt with its tags split, then closes the editor', async () => {
    const { fixture, store } = render();

    press(fixture, '+ new prompt');
    await fill(fixture, 'title', '  Triage  ');
    await fill(fixture, 'prompt', 'quote the first error');
    await fill(fixture, 'category', 'Ops');
    await fill(fixture, 'tags', 'ops, triage ,');
    press(fixture, 'save prompt');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith({
      title: 'Triage', body: 'quote the first error', category: 'Ops', notes: '',
      tags: ['ops', 'triage'],
    }, undefined);
    expect(el(fixture).querySelector('.editor')).toBeNull();
  });

  it('opens an existing prompt in the editor and saves it at its own id', async () => {
    const { fixture, store } = render(storeStub([
      prompt('p-1', { title: 'Triage', body: 'old body', tags: ['ops', 'triage'] }),
    ]));
    store.save.mockResolvedValue('p-1');

    press(fixture, 'edit');
    await settle(fixture);
    expect(el(fixture).querySelector<HTMLInputElement>('.editor .input')?.value).toBe('Triage');

    await fill(fixture, 'prompt', 'new body');
    press(fixture, 'save prompt');
    await settle(fixture);

    expect(store.save).toHaveBeenCalledWith(
      expect.objectContaining({ title: 'Triage', body: 'new body', tags: ['ops', 'triage'] }),
      'p-1');
  });

  it('keeps the editor and the text when a save fails — retyping a prompt is the one cost this page must not have', async () => {
    const { fixture, store } = render();
    store.save.mockResolvedValue('');

    press(fixture, '+ new prompt');
    await fill(fixture, 'title', 'Triage');
    await fill(fixture, 'prompt', 'quote the first error');
    press(fixture, 'save prompt');
    await settle(fixture);

    expect(el(fixture).querySelector('.editor')).toBeTruthy();
    expect(el(fixture).querySelector<HTMLTextAreaElement>('.editor textarea.body')?.value)
      .toBe('quote the first error');
  });

  it('asks before deleting, and does nothing when the answer is no', async () => {
    const { fixture, store } = render(storeStub([prompt('p-1', { title: 'Triage' })]));
    const confirm = stubConfirm(false);

    press(fixture, 'delete');
    await settle(fixture);
    expect(store.remove).not.toHaveBeenCalled();

    confirm.mockResolvedValue(true);
    press(fixture, 'delete');
    await settle(fixture);

    expect(confirm).toHaveBeenCalledWith(
      { title: 'delete prompt', message: 'Delete "Triage"? This cannot be undone.' });
    expect(store.remove).toHaveBeenCalledWith('p-1');
    confirm.mockRestore();
  });

  // ── copy ─────────────────────────────────────────────────────────────────
  describe('copy', () => {
    beforeEach(() => {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: vi.fn().mockResolvedValue(undefined) },
        configurable: true,
      });
    });

    afterEach(() =>
      Reflect.deleteProperty(navigator as unknown as Record<string, unknown>, 'clipboard'));

    it('copies the body alone, and confirms on the row that was clicked', async () => {
      const { fixture } = render(storeStub([
        prompt('p-1', { title: 'Triage', body: 'quote the first error' }),
        prompt('p-2', { title: 'Review' }),
      ]));

      button(fixture, 'copy', row(fixture, 'Triage')).click();
      await settle(fixture);

      // the title is deliberately left out: pasted with the body it would read as
      // part of the instruction
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith('quote the first error');
      expect(row(fixture, 'Triage').textContent).toContain('copied');
      expect(row(fixture, 'Review').textContent).not.toContain('copied');
    });
  });
});

describe('splitTags', () => {
  it('takes tags as an operator types them and drops what is left over', () => {
    expect(splitTags('ops, triage')).toEqual(['ops', 'triage']);
    expect(splitTags(' ops ,, \n triage \n')).toEqual(['ops', 'triage']);
    expect(splitTags('   ')).toEqual([]);
  });
});

/** Types into the page's search box. */
const type = async (fixture: { nativeElement: unknown; detectChanges(): void }, value: string) => {
  const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('.filter .find')!;
  input.value = value;
  input.dispatchEvent(new Event('input'));
  fixture.detectChanges();
};
