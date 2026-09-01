import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PromptGroupStore } from '../core/store/prompt-group-store';
import { PromptStore } from '../core/store/prompt-store';
import { Prompt, PromptGroup } from '../core/models';
import { Reveal } from '../shared/reveal';
import { copyText } from '../shared/copy-text';
import { fileIntoSections, groupHolding } from '../core/filing';
import { ago } from '../core/format';

/** How long a row stays marked as copied — long enough to read, short enough not to linger. */
const COPIED_MS = 1200;

/** How much of a prompt a collapsed row shows. */
const PREVIEW_CHARS = 160;

export type PromptView = 'compact' | 'expanded';

const VIEW_KEY = 'mc-prompt-view';



/**
 * The prompt library — a dictionary of text worth keeping, with a category, notes and tags
 * so it can be found again.
 *
 * Two ways to read it, because the two questions are different. **Compact** answers "which
 * prompt was it?" — one row each, a one-line preview, everything on screen at once.
 * **Expanded** answers "what does it actually say?" — every body open, for comparing two of
 * them or reading one properly. Either way a single row can be opened or shut on its own,
 * which is what {@link opened} holds: it stores the exceptions to the current view rather
 * than the state of every row, so switching view is a clean reset instead of a merge.
 *
 * The view is a preference, so it persists; which rows are open is not, so it does not.
 */
@Component({
  selector: 'mc-prompts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Reveal],
  templateUrl: './prompts.html',
  styleUrl: './prompts.scss',
})
export class PromptsPage {
  protected readonly prompts = inject(PromptStore);
  protected readonly groups = inject(PromptGroupStore);
  protected readonly ago = ago;

  protected readonly query = signal('');
  /** Category filter, or null for the whole library. */
  protected readonly category = signal<string | null>(null);
  protected readonly view = signal<PromptView>(this.savedView());
  /** Rows whose openness differs from what the view would give them. */
  protected readonly opened = signal<ReadonlySet<string>>(new Set());
  /** The prompt last copied, so one row can confirm without a toast. */
  protected readonly copied = signal<string | null>(null);

  protected readonly editorOpen = signal(false);
  /** The prompt being edited, or null while composing a new one. */
  protected readonly editId = signal<string | null>(null);
  protected readonly saving = signal(false);

  // Plain fields, not signals: the editor's own template writes them through
  // `ngModel`, and that event is what re-evaluates `canSave()`.
  protected fTitle = '';
  protected fCategory = '';
  protected fTags = '';
  protected fNotes = '';
  protected fBody = '';

  protected readonly visible = computed(() => {
    const category = this.category();
    const needle = this.query().trim().toLowerCase();
    return this.prompts.prompts().filter(p => {
      if (category && p.category !== category) return false;
      if (!needle) return true;
      return [p.title, p.body, p.notes, p.category, ...p.tags]
        .some(field => field.toLowerCase().includes(needle));
    });
  });

  /** Whether anything is narrowing the list. An empty group still shows its header when
   *  nothing is filtered — a group you just made has to be visible to be filled — but a
   *  search matching none of its prompts hides it, because a header with no rows under it
   *  reads as a match that is not there. */
  protected readonly filtering = computed(() =>
    !!this.query().trim() || this.category() !== null);

  protected readonly sections = computed(() => fileIntoSections(
    this.visible(), this.groups.groups(), g => g.promptIds, this.filtering(), g => g.id));

  // ── the group editor ──────────────────────────────────────────────────────
  protected readonly groupEditorOpen = signal(false);
  /** The group being edited, or null while composing a new one. */
  protected readonly groupEditId = signal<string | null>(null);
  protected readonly groupSaving = signal(false);

  protected gName = '';
  protected gDescription = '';
  /** A signal, unlike the fields above: chips write it, not `ngModel`. */
  protected readonly gPromptIds = signal<string[]>([]);

  private copiedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // LiveSync loads these at boot; this covers a deep link that lands here first
    void this.prompts.refresh();
    void this.groups.refresh();
    // a pending timer would wake a destroyed component and write to its signal
    inject(DestroyRef).onDestroy(() => {
      if (this.copiedTimer) clearTimeout(this.copiedTimer);
    });
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected clearFilters(): void {
    this.query.set('');
    this.category.set(null);
  }

  protected setView(view: PromptView): void {
    if (this.view() === view) return;
    this.view.set(view);
    this.opened.set(new Set());   // the per-row exceptions were relative to the old view
    try { localStorage.setItem(VIEW_KEY, view); } catch { /* private mode */ }
  }

  private savedView(): PromptView {
    try {
      return localStorage.getItem(VIEW_KEY) === 'expanded' ? 'expanded' : 'compact';
    } catch { /* private mode */ }
    return 'compact';
  }

  /** Expanded shows every body; a row in {@link opened} is the exception either way. */
  protected showBody(prompt: Prompt): boolean {
    return this.opened().has(prompt.id) !== (this.view() === 'expanded');
  }

  /** Several rows may be open at once: comparing two prompts is the common reason to
   *  open one, and an accordion that shuts the other makes that impossible. */
  protected toggleOpen(prompt: Prompt): void {
    this.opened.update(open => {
      const next = new Set(open);
      if (!next.delete(prompt.id)) next.add(prompt.id);
      return next;
    });
  }

  protected preview(prompt: Prompt): string {
    const oneLine = prompt.body.replace(/\s+/g, ' ').trim();
    return oneLine.length > PREVIEW_CHARS ? `${oneLine.slice(0, PREVIEW_CHARS)}…` : oneLine;
  }

  protected async copy(prompt: Prompt): Promise<void> {
    // the body alone: it is what gets pasted, and a title pasted with it would be read
    // as part of the instruction
    if (!await copyText(prompt.body)) return;
    this.copied.set(prompt.id);
    if (this.copiedTimer) clearTimeout(this.copiedTimer);
    this.copiedTimer = setTimeout(() => this.copied.set(null), COPIED_MS);
  }

  protected newPrompt(): void {
    this.editId.set(null);
    this.fTitle = '';
    this.fCategory = this.category() ?? '';
    this.fTags = '';
    this.fNotes = '';
    this.fBody = '';
    this.editorOpen.set(true);
  }

  protected edit(prompt: Prompt): void {
    this.editId.set(prompt.id);
    this.fTitle = prompt.title;
    this.fCategory = prompt.category;
    this.fTags = prompt.tags.join(', ');
    this.fNotes = prompt.notes;
    this.fBody = prompt.body;
    this.editorOpen.set(true);
  }

  protected cancel(): void {
    this.editorOpen.set(false);
    this.editId.set(null);
  }

  protected canSave(): boolean {
    return !!this.fTitle.trim() && !!this.fBody.trim() && !this.saving();
  }

  protected async save(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    const id = await this.prompts.save({
      title: this.fTitle.trim(),
      body: this.fBody,
      category: this.fCategory.trim(),
      notes: this.fNotes.trim(),
      tags: splitTags(this.fTags),
    }, this.editId() ?? undefined);
    this.saving.set(false);
    // a failed save keeps the editor open with the text still in it — retyping a prompt
    // because the backend blinked is the one thing this page must never cost
    if (id) this.cancel();
  }

  protected async remove(prompt: Prompt): Promise<void> {
    if (!confirm(`Delete prompt "${prompt.title}"? This cannot be undone.`)) return;
    if (!await this.prompts.remove(prompt.id)) return;
    if (this.editId() === prompt.id) this.cancel();
  }

  // ── groups ───────────────────────────────────────────────────────────────

  protected filedElsewhere(promptId: string): string {
    return groupHolding(this.groups.groups(), g => g.promptIds, promptId, this.groupEditId());
  }

  protected newGroup(): void {
    this.groupEditId.set(null);
    this.gName = '';
    this.gDescription = '';
    this.gPromptIds.set([]);
    this.groupEditorOpen.set(true);
  }

  protected editGroup(group: PromptGroup): void {
    this.groupEditId.set(group.id);
    this.gName = group.name;
    this.gDescription = group.description;
    this.gPromptIds.set([...group.promptIds]);
    this.groupEditorOpen.set(true);
  }

  protected cancelGroup(): void {
    this.groupEditorOpen.set(false);
    this.groupEditId.set(null);
  }

  protected toggleGroupPrompt(id: string): void {
    this.gPromptIds.update(ids => ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id]);
  }

  protected canSaveGroup(): boolean {
    return !this.groupSaving() && !!this.gName.trim();
  }

  protected async saveGroup(): Promise<void> {
    if (!this.canSaveGroup()) return;
    this.groupSaving.set(true);
    const id = await this.groups.save({
      name: this.gName.trim(),
      description: this.gDescription.trim(),
      promptIds: this.gPromptIds(),
    }, this.groupEditId() ?? undefined);
    this.groupSaving.set(false);
    if (id) this.cancelGroup();
  }

  protected async removeGroup(group: PromptGroup): Promise<void> {
    if (!confirm(
      `Delete the group "${group.name}"? Its prompts stay in the library — only the filing goes.`
    )) return;
    if (!await this.groups.remove(group.id)) return;
    if (this.groupEditId() === group.id) this.cancelGroup();
  }
}

/** Comma- or newline-separated tags, as typed. The backend folds and dedupes them. */
export function splitTags(raw: string): string[] {
  return raw.split(/[,\n]/).map(t => t.trim()).filter(Boolean);
}
