import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Confirm } from '../shared/confirm';
import { Router } from '@angular/router';
import { InferenceEndpointStore } from '../core/store/inference-endpoint-store';
import { ProviderStore } from '../core/store/provider-store';
import { TemplateStore } from '../core/store/template-store';
import { ProfileTemplate } from '../core/models';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { providerOptionFor, providerOptions } from '../shared/provider-resolve';
import { AgentIconView } from '../shared/agent-icon';
import { ProfileDeployDialog } from './profile-deploy-dialog';
import { ProfileEditorPanel } from './profile-editor-panel';
import { ProfileDraft, newProfileDraft, profileDraftFrom } from './profile-editor';

/**
 * Blueprints — author reusable agent profiles (soul, memory, skills, MCP servers,
 * encrypted keys) that can be applied when deploying an agent (see the Agents
 * page "from profile" selector).
 *
 * The page is the list, which blueprint is open, and — once a library outgrows one
 * screen — how to find one again: a search box over everything a blueprint carries,
 * plus one chip row per facet (category, provider, model) and three toggles for what
 * it installs. A facet row appears only when it has more than one value to choose
 * between, so a small library stays a list rather than becoming a filter panel.
 *
 * Editing one is {@link ProfileEditorPanel}'s business, deploying it
 * {@link ProfileDeployDialog}'s, and what a draft is lives in ./profile-editor.
 */
@Component({
  selector: 'mc-agent-profiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Reveal, AgentIconView, ProfileEditorPanel, ProfileDeployDialog],
  templateUrl: './agent-profiles.html',
  styleUrl: './agent-profiles.scss',
})
export class AgentProfilesPage {
  protected readonly providers = inject(ProviderStore);
  private readonly confirm = inject(Confirm);
  protected readonly endpoints = inject(InferenceEndpointStore);
  protected readonly templates = inject(TemplateStore);
  private readonly router = inject(Router);
  protected readonly ago = ago;

  /** The blueprint the editor is open on. A fresh object each time, so the panel
   *  can tell one draft from the next and clear its half-typed rows. */
  protected readonly draft = signal<ProfileDraft>(newProfileDraft());
  protected readonly open = signal(false);   // editor pane visible (mobile + first load)

  protected readonly deployFor = signal<ProfileTemplate | null>(null);

  /**
   * Cards whose detail is showing. Collapsed to begin with: the list is how an
   * operator finds a blueprint, and a description plus a model line plus three
   * chip rows each is more than a name needs to be recognised by.
   */
  private readonly openCards = signal<ReadonlySet<string>>(new Set());

  // ── finding one again ────────────────────────────────────────────────────
  protected readonly query = signal('');
  /** Single-select facets; null means "every value". */
  protected readonly category = signal<string | null>(null);
  protected readonly provider = signal<string | null>(null);
  protected readonly model = signal<string | null>(null);
  /** What a blueprint has to carry. Multi-select, and they narrow together. */
  protected readonly carries = signal<ReadonlySet<Carries>>(new Set());

  /** `providers` is already the store, so these carry the `-Names` suffix. */
  protected readonly providerNames = computed(() => this.facet(t => t.provider));
  protected readonly modelNames = computed(() => this.facet(t => t.model));

  /** True when anything is narrowing the list — what the reset button keys off. */
  protected readonly filtering = computed(() =>
    !!this.query().trim() || !!this.category() || !!this.provider() || !!this.model()
      || this.carries().size > 0);

  /** Whether any blueprint installs anything, so the toggles are worth a row. */
  protected readonly anyCarries = computed(() =>
    this.templates.templates().some(t => CARRIES.some(c => carried(t, c))));

  protected readonly visible = computed(() => {
    const category = this.category();
    const provider = this.provider();
    const model = this.model();
    const carries = this.carries();
    const needle = this.query().trim().toLowerCase();
    return this.templates.templates().filter(t => {
      if (category && t.category !== category) return false;
      if (provider && t.provider !== provider) return false;
      if (model && t.model !== model) return false;
      for (const c of carries) {
        if (!carried(t, c)) return false;
      }
      return !needle || haystack(t).includes(needle);
    });
  });

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected toggleCarries(c: Carries): void {
    this.carries.update(set => {
      const next = new Set(set);
      if (!next.delete(c)) next.add(c);
      return next;
    });
  }

  protected clearFilters(): void {
    this.query.set('');
    this.category.set(null);
    this.provider.set(null);
    this.model.set(null);
    this.carries.set(new Set());
  }

  /** The distinct values of one field, blanks dropped — a blueprint with no model
   *  set is not a facet of its own, it is one you cannot filter by. */
  private facet(of: (t: ProfileTemplate) => string): string[] {
    return [...new Set(this.templates.templates().map(of).filter(Boolean))].sort();
  }

  protected newTemplate(): void {
    this.draft.set(newProfileDraft());
    this.open.set(true);
  }

  protected edit(t: ProfileTemplate): void {
    // a template stores ollama flat; the dropdown lists one option per instance
    const option = providerOptionFor(
      t.provider, t.baseUrl,
      providerOptions(this.providers.llmProviders(), this.endpoints.endpoints()),
      this.endpoints.endpoints());
    this.draft.set(profileDraftFrom(t, option ?? (t.provider || 'nous')));
    this.open.set(true);
  }

  /** The panel mutates the draft it was handed rather than replacing it, so this
   *  is what tells the list a new blueprint now has an id to highlight. */
  protected onSaved(_id: string): void {
    // nothing to change here; being called is the point
  }

  protected closeEditor(): void {
    this.open.set(false);
    this.draft.set(newProfileDraft());
  }

  protected async remove(t: ProfileTemplate): Promise<void> {
    if (!await this.confirm.ask({
      title: 'delete blueprint',
      message: `Delete "${t.name}"? This cannot be undone.`,
    })) return;
    await this.templates.remove(t.id);
    if (this.draft().id === t.id) this.closeEditor();
  }

  protected cardOpen(id: string): boolean {
    return this.openCards().has(id);
  }

  protected toggleCard(id: string): void {
    this.openCards.update(open => {
      const next = new Set(open);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }

  protected openDeploy(t: ProfileTemplate): void {
    this.deployFor.set(t);
  }

  /** Straight to the agent the blueprint just became. */
  protected onDeployed(agentId: string): void {
    this.deployFor.set(null);
    this.router.navigate(['/agents', agentId]);
  }
}

/** The three things a blueprint can install, as the toggles name them. */
export type Carries = 'skills' | 'mcp' | 'keys';

const CARRIES: readonly Carries[] = ['skills', 'mcp', 'keys'];

export function carried(t: ProfileTemplate, what: Carries): boolean {
  switch (what) {
    case 'skills': return t.skills.length > 0;
    case 'mcp': return t.mcpServers.length > 0;
    case 'keys': return t.secrets.length > 0;
  }
}

/** Everything about a blueprint the search box looks through. Deliberately more
 *  than the card shows: an operator hunting the blueprint that carries a given
 *  skill or key knows that word, not the name someone filed it under. */
function haystack(t: ProfileTemplate): string {
  return [
    t.name, t.description, t.category, t.provider, t.model,
    ...t.skills,
    ...t.mcpServers.map(m => m.name),
    ...t.secrets.map(s => s.key),
  ].join(' ').toLowerCase();
}
