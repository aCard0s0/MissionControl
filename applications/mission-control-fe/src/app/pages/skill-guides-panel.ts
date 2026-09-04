import {
  ChangeDetectionStrategy, Component, computed, inject, signal,
} from '@angular/core';
import { Confirm } from '../shared/confirm';
import { FormsModule } from '@angular/forms';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { SkillGuideStore } from '../core/store/skill-guide-store';
import { SkillStore } from '../core/store/skill-store';
import { AgentRef } from '../core/api/agent-ref';
import { DeployedPart, SkillGuide } from '../core/models';
import { DeployDialog } from './deploy-dialog';
import { ago } from '../core/format';

/** What a new guide starts as — the shape that makes an umbrella skill useful. */
const STARTER_BODY = `## When to use this

## How the pieces fit together

## What to watch out for
`;

/**
 * The Guides tab of the Skills page.
 *
 * A guide is prose plus two lists of ids, and deploying one is three things at once: every
 * skill onto the agent, every MCP server linked to it, and the prose written there as an
 * umbrella skill the agent reads. That last part is why the editor is a body and not a
 * description field — an agent choosing between skills is the real audience.
 *
 * Its own component rather than more of {@link SkillsPage}: the two tabs share a filter bar
 * and nothing else, and folding a second editor into that page would double it.
 */
@Component({
  selector: 'mc-skill-guides-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, DeployDialog],
  templateUrl: './skill-guides-panel.html',
  styleUrl: './skill-guides-panel.scss',
})
export class SkillGuidesPanel {
  protected readonly guides = inject(SkillGuideStore);
  private readonly confirm = inject(Confirm);
  protected readonly skills = inject(SkillStore);
  protected readonly catalog = inject(McpCatalogStore);
  protected readonly ago = ago;

  protected readonly query = signal('');
  protected readonly category = signal<string | null>(null);
  protected readonly expanded = signal<string | null>(null);
  protected readonly deploying = signal<SkillGuide | null>(null);

  /** A field, not an inline arrow — see the note on the dialog's `run` input. */
  protected readonly deployToAgent = async (agent: AgentRef): Promise<DeployedPart[] | null> => {
    const guide = this.deploying();
    return guide ? this.guides.deploy(guide.id, agent) : null;
  };

  protected readonly editorOpen = signal(false);
  protected readonly editId = signal<string | null>(null);
  protected readonly saving = signal(false);

  // Plain fields for `ngModel`, signals for what buttons change — the same split the
  // skills editor uses, and for the same reason.
  protected fName = '';
  protected fDescription = '';
  protected fCategory = '';
  protected fBody = '';
  protected readonly fSkillIds = signal<string[]>([]);
  protected readonly fMcpIds = signal<string[]>([]);

  protected readonly visible = computed(() => {
    const category = this.category();
    const needle = this.query().trim().toLowerCase();
    return this.guides.guides().filter(g => {
      if (category && g.category !== category) return false;
      if (!needle) return true;
      return [g.name, g.description, g.body, g.category]
        .some(field => field.toLowerCase().includes(needle));
    });
  });

  constructor() {
    void this.guides.refresh();
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected clearFilters(): void {
    this.query.set('');
    this.category.set(null);
  }

  protected toggleExpanded(guide: SkillGuide): void {
    this.expanded.update(open => (open === guide.id ? null : guide.id));
  }

  /** The names behind a guide's ids, and a marker for any that are gone — the same thing
   *  the deploy will report, shown before the operator clicks rather than after. */
  protected skillNames(guide: SkillGuide): Named[] {
    return named(guide.skillIds, id => this.skills.byId(id)?.name);
  }

  protected serverNames(guide: SkillGuide): Named[] {
    return named(guide.mcpServerIds, id => this.catalog.servers().find(s => s.id === id)?.name);
  }

  // ── editor ───────────────────────────────────────────────────────────────

  protected newGuide(): void {
    this.editId.set(null);
    this.fName = '';
    this.fDescription = '';
    this.fCategory = this.category() ?? '';
    this.fBody = STARTER_BODY;
    this.fSkillIds.set([]);
    this.fMcpIds.set([]);
    this.editorOpen.set(true);
  }

  protected edit(guide: SkillGuide): void {
    this.editId.set(guide.id);
    this.fName = guide.name;
    this.fDescription = guide.description;
    this.fCategory = guide.category;
    this.fBody = guide.body;
    this.fSkillIds.set([...guide.skillIds]);
    this.fMcpIds.set([...guide.mcpServerIds]);
    this.editorOpen.set(true);
  }

  protected cancel(): void {
    this.editorOpen.set(false);
    this.editId.set(null);
  }

  protected toggleSkill(id: string): void {
    this.fSkillIds.update(ids => toggle(ids, id));
  }

  protected toggleServer(id: string): void {
    this.fMcpIds.update(ids => toggle(ids, id));
  }

  protected canSave(): boolean {
    return !this.saving() && !!this.fName.trim() && !!this.fBody.trim();
  }

  protected async save(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    const id = await this.guides.save({
      name: this.fName.trim(),
      description: this.fDescription.trim(),
      body: this.fBody,
      category: this.fCategory.trim(),
      skillIds: this.fSkillIds(),
      mcpServerIds: this.fMcpIds(),
    }, this.editId() ?? undefined);
    this.saving.set(false);
    // a failed save keeps the editor open with the prose still in it
    if (id) this.cancel();
  }

  protected async remove(guide: SkillGuide): Promise<void> {
    if (!await this.confirm.ask({
      title: 'delete guide',
      message: `Delete guide "${guide.name}"? Anything it already deployed stays on its agents.`,
    })) return;
    if (!await this.guides.remove(guide.id)) return;
    if (this.editId() === guide.id) this.cancel();
  }
}

/** Adds or removes one id, keeping the order the operator picked in — the umbrella skill
 *  lists the parts in exactly this order. */
function toggle(ids: string[], id: string): string[] {
  return ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id];
}

interface Named { name: string; missing: boolean }

/** An id shows as its name, or as the bare id when nothing answers to it any more. */
function named(ids: string[], lookup: (id: string) => string | undefined): Named[] {
  return ids.map(id => {
    const name = lookup(id);
    return { name: name ?? id, missing: !name };
  });
}
