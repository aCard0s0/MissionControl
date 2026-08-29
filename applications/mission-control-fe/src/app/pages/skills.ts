import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { SkillStore } from '../core/store/skill-store';
import { AgentRef } from '../core/api/agent-ref';
import { DeployedPart, Skill, SkillFile, SkillKind } from '../core/models';
import { Reveal } from '../shared/reveal';
import { DeployDialog } from './deploy-dialog';
import { SkillGuidesPanel } from './skill-guides-panel';
import { ago } from '../core/format';

export type SkillsTab = 'skills' | 'guides';

/** Hermes finds a skill by this file. A local skill without one cannot be loaded. */
const SKILL_MD = 'SKILL.md';

/** What a new local skill starts as, so the first save is one edit away rather than
 *  a blank textarea and a guess at the frontmatter. */
const STARTER_SKILL_MD = `---
name: my-skill
description: what this skill does, and when an agent should reach for it
version: 1.0
---

# My skill

## When to use this

## How to use it
`;

/**
 * The skill library — skills the dashboard holds, deployable onto any agent.
 *
 * The page is built around one distinction, because it decides everything else about a
 * row. A **hub** skill is a pointer: the Skills Hub owns its content, so the library keeps
 * only the id and a deploy shells `hermes skills install`. A **local** skill is one nothing
 * else can install — authored here, or written by an agent's own curator — so the library
 * owns its files and a deploy writes them out. That is why the files editor disappears for
 * a hub row: there is nothing there to edit, and a copy kept here would go stale the moment
 * the Hub moved.
 *
 * The per-agent Skills tab is the other half of this and stays where it is: it answers
 * "what does this agent have", which is a question about one container. This page answers
 * "what do I have to give it".
 */
@Component({
  selector: 'mc-skills',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Reveal, DeployDialog, SkillGuidesPanel],
  templateUrl: './skills.html',
  styleUrl: './skills.scss',
})
export class SkillsPage {
  /** `?tab=` — how a link opens straight on Guides. It seeds {@link activeTab} rather than
   *  owning it: pressing a tab afterwards stays local, so flipping between the two neither
   *  rewrites the URL nor fills the back button. The same contract agent-detail uses. */
  readonly tab = input<string | null>(null);

  protected readonly tabs: SkillsTab[] = ['skills', 'guides'];
  protected readonly activeTab = signal<SkillsTab>('skills');

  protected readonly skills = inject(SkillStore);
  protected readonly ago = ago;

  protected readonly query = signal('');
  /** Category filter, or null for the whole library. */
  protected readonly category = signal<string | null>(null);
  /** Kind filter, or null for both. */
  protected readonly kind = signal<SkillKind | null>(null);
  /** The row whose files are expanded, or null. */
  protected readonly expanded = signal<string | null>(null);
  /** The skill being deployed, or null while no dialog is open. */
  protected readonly deploying = signal<Skill | null>(null);

  /** A field, not an inline arrow in the template: the dialog's `run` input would otherwise
   *  take a new function identity on every change detection pass. A single skill has nothing
   *  to enumerate, so success answers an empty report. */
  protected readonly deployToAgent = async (agent: AgentRef): Promise<DeployedPart[] | null> => {
    const skill = this.deploying();
    if (!skill) return null;
    return await this.skills.deploy(skill.id, agent) ? [] : null;
  };

  protected readonly editorOpen = signal(false);
  /** The skill being edited, or null while composing a new one. */
  protected readonly editId = signal<string | null>(null);
  protected readonly saving = signal(false);

  // Plain fields, not signals: the editor's own template writes them through `ngModel`,
  // and that event is what re-evaluates `canSave()`.
  protected fKind: SkillKind = 'local';
  protected fName = '';
  protected fDescription = '';
  protected fCategory = '';
  protected fRepoUrl = '';
  protected fVersion = '';
  /** The file set being edited. A signal, unlike the fields above, because rows are added
   *  and removed by buttons rather than by `ngModel`. */
  protected readonly fFiles = signal<SkillFile[]>([]);

  protected readonly visible = computed(() => {
    const category = this.category();
    const kind = this.kind();
    const needle = this.query().trim().toLowerCase();
    return this.skills.skills().filter(s => {
      if (category && s.category !== category) return false;
      if (kind && s.kind !== kind) return false;
      if (!needle) return true;
      return [s.name, s.description, s.category, s.repoUrl, ...s.files.map(f => f.path)]
        .some(field => field.toLowerCase().includes(needle));
    });
  });

  constructor() {
    // LiveSync loads the library at boot; this covers a deep link that lands here first
    void this.skills.refresh();
    effect(() => {
      const wanted = this.tab();
      if (wanted && this.tabs.includes(wanted as SkillsTab)) {
        untracked(() => this.activeTab.set(wanted as SkillsTab));
      }
    });
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected clearFilters(): void {
    this.query.set('');
    this.category.set(null);
    this.kind.set(null);
  }

  protected toggleExpanded(skill: Skill): void {
    this.expanded.update(open => (open === skill.id ? null : skill.id));
  }

  // ── editor ───────────────────────────────────────────────────────────────

  protected newSkill(): void {
    this.editId.set(null);
    this.fKind = 'local';
    this.fName = '';
    this.fDescription = '';
    this.fCategory = this.category() ?? '';
    this.fRepoUrl = '';
    this.fVersion = '';
    this.fFiles.set([{ path: SKILL_MD, body: STARTER_SKILL_MD }]);
    this.editorOpen.set(true);
  }

  protected edit(skill: Skill): void {
    this.editId.set(skill.id);
    this.fKind = skill.kind;
    this.fName = skill.name;
    this.fDescription = skill.description;
    this.fCategory = skill.category;
    this.fRepoUrl = skill.repoUrl;
    this.fVersion = skill.version;
    this.fFiles.set(skill.files.map(f => ({ ...f })));
    this.editorOpen.set(true);
  }

  protected cancel(): void {
    this.editorOpen.set(false);
    this.editId.set(null);
  }

  /** Switching to hub drops the file set rather than hiding it: a hub row that kept its
   *  files would be rejected on save, and the reason would not be on screen. */
  protected setKind(kind: SkillKind): void {
    this.fKind = kind;
    if (kind === 'hub') {
      this.fFiles.set([]);
    } else if (!this.fFiles().length) {
      this.fFiles.set([{ path: SKILL_MD, body: STARTER_SKILL_MD }]);
    }
  }

  protected addFile(): void {
    this.fFiles.update(files => [...files, { path: '', body: '' }]);
  }

  protected removeFile(index: number): void {
    this.fFiles.update(files => files.filter((_, i) => i !== index));
  }

  protected setFilePath(index: number, path: string): void {
    this.fFiles.update(files => files.map((f, i) => (i === index ? { ...f, path } : f)));
  }

  protected setFileBody(index: number, body: string): void {
    this.fFiles.update(files => files.map((f, i) => (i === index ? { ...f, body } : f)));
  }

  /** A local skill needs a SKILL.md, so the page says so before the backend does. */
  protected hasSkillMd(): boolean {
    return this.fFiles().some(f => f.path.trim() === SKILL_MD);
  }

  protected canSave(): boolean {
    if (this.saving() || !this.fName.trim()) return false;
    return this.fKind === 'hub' || this.hasSkillMd();
  }

  protected async save(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    const id = await this.skills.save({
      kind: this.fKind,
      name: this.fName.trim(),
      description: this.fDescription.trim(),
      category: this.fCategory.trim(),
      repoUrl: this.fRepoUrl.trim(),
      version: this.fVersion.trim(),
      files: this.fKind === 'hub'
        ? []
        : this.fFiles().filter(f => f.path.trim()).map(f => ({ ...f, path: f.path.trim() })),
    }, this.editId() ?? undefined);
    this.saving.set(false);
    // a failed save keeps the editor open with the files still in it — retyping a skill
    // because the backend blinked is the one thing this page must never cost
    if (id) this.cancel();
  }

  protected async remove(skill: Skill): Promise<void> {
    if (!confirm(
      `Delete "${skill.name}" from the library? Any copy already on an agent stays there.`
    )) return;
    if (!await this.skills.remove(skill.id)) return;
    if (this.editId() === skill.id) this.cancel();
  }
}
