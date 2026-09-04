import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { Confirm } from '../shared/confirm';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { SkillGroupStore } from '../core/store/skill-group-store';
import { SkillGuideStore } from '../core/store/skill-guide-store';
import { SkillStore } from '../core/store/skill-store';
import { AgentRef } from '../core/api/agent-ref';
import {
  DeployedPart, Skill, SkillFile, SkillGroup, SkillGuide, SkillKind, Upstream, UpstreamStatus,
} from '../core/models';
import { Reveal } from '../shared/reveal';
import { DeployDialog } from './deploy-dialog';
import { SkillGuidesPanel } from './skill-guides-panel';
import { fileIntoSections, GroupDraft, groupHolding } from '../core/filing';
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

/** The backend's per-file cap (`SkillController.SkillFileRequest.body`). */
const MAX_FILE_CHARS = 200_000;
/** The backend's per-skill budget for a deploy (`HermesSkills.MAX_SKILL_BYTES`). */
const MAX_SKILL_BYTES = 512 * 1024;

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
  imports: [NgTemplateOutlet, FormsModule, Reveal, DeployDialog, SkillGuidesPanel],
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
  private readonly confirm = inject(Confirm);
  protected readonly groups = inject(SkillGroupStore);
  protected readonly guides = inject(SkillGuideStore);
  protected readonly ago = ago;

  protected readonly query = signal('');
  /** Category filter, or null for the whole library. */
  protected readonly category = signal<string | null>(null);
  /** Kind filter, or null for both. */
  protected readonly kind = signal<SkillKind | null>(null);
  /** The row whose detail — description, then files — is open, or null. */
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

  // ── the group editor ──────────────────────────────────────────────────────
  /** The group editor's state — open, editing which, and which skills are picked. */
  protected readonly groupDraft = new GroupDraft();

  /** The guide this group points at, or ''. Here rather than on the draft: the other two
   *  group types have nothing like it. A signal for the same reason `fFiles` is one —
   *  chips write it, not `ngModel`. */
  protected readonly gGuideId = signal('');

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

  /** Whether anything is narrowing the list. An empty group still shows its header when
   *  nothing is filtered — a group you just made and have not filled yet has to be visible
   *  to be filled — but a search that matches none of its skills hides it, because a header
   *  with no rows under it reads as a match that is not there. */
  protected readonly filtering = computed(() =>
    !!this.query().trim() || this.category() !== null || this.kind() !== null);

  protected readonly sections = computed(() => fileIntoSections(
    this.visible(), this.groups.groups(), g => g.skillIds, this.filtering(), g => g.id));

  /** The guide a group points at, or null — either because it points at none, or because the
   *  guide has been deleted since. The two cases read differently on the header. */
  protected guideOf(group: SkillGroup): SkillGuide | null {
    return group.guideId ? this.guides.byId(group.guideId) : null;
  }

  protected filedElsewhere(skillId: string): string {
    return groupHolding(this.groups.groups(), g => g.skillIds, skillId, this.groupDraft.editId());
  }

  constructor() {
    // LiveSync loads these at boot; this covers a deep link that lands here first
    void this.skills.refresh();
    void this.groups.refresh();
    void this.guides.refresh();
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

  /** Whether a row has anything to open. The description moved behind the disclosure to
   *  keep the list scannable, so a hub row with one is expandable too — but a row with
   *  neither description nor files must not offer a caret that reveals an empty box. */
  protected hasDetail(skill: Skill): boolean {
    return !!skill.description || skill.files.length > 0;
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

  /**
   * The name SKILL.md's own frontmatter declares, when it declares one.
   *
   * <p>Hermes resolves a skill by its frontmatter name and falls back to the directory only
   * when there is none, so a body still carrying the starter's `name:` deploys into
   * `skills/<row name>/` and then shows up on the agent under the other name. Reported
   * rather than rewritten: overriding the directory name is a legitimate thing an author
   * does, and silently editing someone's frontmatter to match a field is worse than saying
   * the two disagree.
   */
  protected frontmatterName(): string {
    const md = this.fFiles().find(f => f.path.trim() === SKILL_MD)?.body ?? '';
    if (!md.startsWith('---')) return '';
    const end = md.indexOf('\n---', 3);
    const declared = /^name:\s*(.+)$/m.exec(end > 0 ? md.slice(3, end) : '');
    return declared ? declared[1].trim() : '';
  }

  /** True when SKILL.md names a different skill than this row does. */
  protected nameDisagrees(): boolean {
    const declared = this.frontmatterName();
    return this.fKind === 'local' && !!declared && !!this.fName.trim()
      && declared !== this.fName.trim();
  }

  /** A local skill needs a SKILL.md, so the page says so before the backend does. */
  protected hasSkillMd(): boolean {
    return this.fFiles().some(f => f.path.trim() === SKILL_MD);
  }

  /**
   * The first file over the backend's limits, named — or null. Mirrors the server: a file body
   * is at most {@link MAX_FILE_CHARS} (`SkillController.SkillFileRequest`) and a skill's files
   * together at most {@link MAX_SKILL_BYTES} (`HermesSkills.MAX_SKILL_BYTES`), so a paste that
   * would be refused is refused here with the reason on screen instead of in a 400.
   */
  protected tooLarge(): string | null {
    if (this.fKind !== 'local') return null;
    let total = 0;
    for (const f of this.fFiles()) {
      if (f.body.length > MAX_FILE_CHARS) {
        return `${f.path.trim() || 'a file'} is over ${MAX_FILE_CHARS.toLocaleString()} characters`;
      }
      total += new TextEncoder().encode(f.body).length;
    }
    return total > MAX_SKILL_BYTES ? `files total over ${MAX_SKILL_BYTES / 1024} KB` : null;
  }

  protected canSave(): boolean {
    if (this.saving() || !this.fName.trim()) return false;
    return this.fKind === 'hub' || (this.hasSkillMd() && !this.tooLarge());
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

  /** The chip's text. `update` names the version, because "which one" is the question an
   *  operator asks next and the answer is already in hand. */
  protected upstreamLabel(up: Upstream): string {
    switch (up.status) {
      case 'checking': return 'checking…';
      case 'current': return 'up to date';
      case 'update': return `update: ${up.latest}`;
      case 'unknown': return up.latest ? `upstream ${up.latest}` : 'no version set';
      case 'unsupported': return 'not checkable';
      default: return 'check failed';
    }
  }

  protected upstreamHint(status: UpstreamStatus): string {
    switch (status) {
      case 'unknown': return 'set a version on this skill to compare against';
      case 'unsupported': return 'only github.com repositories can be checked';
      case 'unavailable': return 'github could not be reached — try again shortly';
      default: return '';
    }
  }

  protected async remove(skill: Skill): Promise<void> {
    if (!await this.confirm.ask({
      title: 'delete skill',
      message: `Delete "${skill.name}" from the library? Any copy already on an agent stays there.`,
    })) return;
    if (!await this.skills.remove(skill.id)) return;
    if (this.editId() === skill.id) this.cancel();
  }

  // ── groups ───────────────────────────────────────────────────────────────

  protected newGroup(): void {
    this.groupDraft.begin();
    this.gGuideId.set('');
  }

  protected editGroup(group: SkillGroup): void {
    this.groupDraft.begin(group, group.skillIds);
    this.gGuideId.set(group.guideId);
  }

  /** Pressing the guide already picked clears it — the association is the optional half, and
   *  a picker you cannot un-pick would make it compulsory in practice. */
  protected pickGuide(id: string): void {
    this.gGuideId.update(current => current === id ? '' : id);
  }

  protected saveGroup(): Promise<void> {
    return this.groupDraft.save(this.groups, f => ({
      name: f.name, description: f.description, skillIds: f.ids, guideId: this.gGuideId(),
    }));
  }

  protected async removeGroup(group: SkillGroup): Promise<void> {
    if (!await this.confirm.ask({
      title: 'delete group',
      message: `Delete the group "${group.name}"? Its skills stay in the library — only the filing goes.`,
    })) return;
    if (await this.groups.remove(group.id)) this.groupDraft.closeIf(group.id);
  }
}
