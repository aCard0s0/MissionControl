import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentSkillStore } from '../core/store/agent-skill-store';
import { AgentStore } from '../core/store/agent-store';
import { SkillStore } from '../core/store/skill-store';
import { AgentProfile, SkillContent, SkillRef } from '../core/models';

/** How long the "saved ✓" chip stays up after a write lands. */
const SAVED_CHIP_MS = 1800;

/**
 * The profile's Skills tab: enable/disable, install, remove, and read or edit one
 * skill's SKILL.md inline. Only one skill is expanded at a time, so a single set
 * of editor signals covers the whole list.
 */
@Component({
  selector: 'mc-agent-skills-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './agent-skills-panel.html',
  styleUrl: './agent-skills-panel.scss',
})
export class AgentSkillsPanel {
  readonly agent = input.required<AgentProfile>();

  protected readonly skills = inject(AgentSkillStore);
  private readonly agents = inject(AgentStore);
  private readonly library = inject(SkillStore);

  // add form
  protected skillName = '';
  protected skillSource: 'hub' | 'user' = 'hub';

  // SKILL.md viewer / editor
  protected readonly expandedSkill = signal<string | null>(null);   // skill id, or null
  protected readonly skillContent = signal<SkillContent | null>(null);
  protected readonly skillBody = signal('');
  protected readonly skillLoading = signal(false);
  protected readonly skillSaving = signal(false);
  protected readonly skillSaved = signal(false);
  protected readonly skillDirty = computed(() => {
    const c = this.skillContent();
    return c !== null && this.skillBody() !== c.body;
  });

  protected readonly enabledCount = computed(() =>
    this.agent().skills.filter(s => s.enabled).length);

  /** The skill currently being copied into the library, so one row can show it. */
  protected readonly importing = signal<string | null>(null);

  /**
   * Copies this skill into the global library, so it can be deployed to other agents.
   *
   * The reason this button exists: hermes has no `skills create`, so a skill the agent's
   * own curator wrote has no id anything can install it by. Reading its files into the
   * library is the only way it travels.
   */
  protected async saveToLibrary(s: SkillRef): Promise<void> {
    const resolved = this.agents.resolve(this.agent().id);
    if (!resolved || this.importing()) return;
    this.importing.set(s.id);
    await this.library.importFrom(resolved.ref, s.name);
    this.importing.set(null);
  }

  protected addSkill(): void {
    const name = this.skillName.trim();
    if (!name) return;
    this.skills.add(this.agent().id, {
      name, source: this.skillSource, version: '0.1.0',
      description: this.skillSource === 'hub' ? 'Installed from Skills Hub' : 'Local user skill',
      enabled: true,
    });
    this.skillName = '';
  }

  /** Expand a skill row and load its SKILL.md for inspection/editing. */
  protected async viewSkill(s: SkillRef): Promise<void> {
    if (this.expandedSkill() === s.id) { this.cancelSkillEdit(); return; }
    this.expandedSkill.set(s.id);
    this.skillContent.set(null);
    this.skillBody.set('');
    this.skillSaved.set(false);
    this.skillLoading.set(true);
    const content = await this.skills.content(this.agent().id, s);
    // ignore the response if the user collapsed or switched skills mid-load
    if (this.expandedSkill() !== s.id) { this.skillLoading.set(false); return; }
    this.skillContent.set(content);
    this.skillBody.set(content?.body ?? '');
    this.skillLoading.set(false);
  }

  protected async saveSkill(s: SkillRef): Promise<void> {
    if (!this.skillDirty() || this.skillSaving()) return;
    this.skillSaving.set(true);
    const ok = await this.skills.saveContent(this.agent().id, s, this.skillBody());
    this.skillSaving.set(false);
    if (!ok) return;
    this.skillContent.update(c => c ? { ...c, body: this.skillBody() } : c);
    this.skillSaved.set(true);
    setTimeout(() => this.skillSaved.set(false), SAVED_CHIP_MS);
  }

  protected cancelSkillEdit(): void {
    this.expandedSkill.set(null);
    this.skillContent.set(null);
    this.skillBody.set('');
    this.skillLoading.set(false);
  }
}
