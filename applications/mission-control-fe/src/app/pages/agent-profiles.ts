import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { ProviderStore } from '../core/store/provider-store';
import { TemplateStore } from '../core/store/template-store';
import { ProfileTemplate } from '../core/models';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { providerOptionFor, providerOptions } from '../shared/provider-resolve';
import { ProfileDeployDialog } from './profile-deploy-dialog';
import { ProfileEditorPanel } from './profile-editor-panel';
import { ProfileDraft, newProfileDraft, profileDraftFrom } from './profile-editor';

/**
 * Blueprints — author reusable agent profiles (soul, memory, skills, MCP servers,
 * encrypted keys) that can be applied when deploying an agent (see the Agents
 * page "from profile" selector).
 *
 * The page is the list and which blueprint is open. Editing one is
 * {@link ProfileEditorPanel}'s business, deploying it {@link ProfileDeployDialog}'s,
 * and what a draft is lives in ./profile-editor.
 */
@Component({
  selector: 'mc-agent-profiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [Reveal, ProfileEditorPanel, ProfileDeployDialog],
  templateUrl: './agent-profiles.html',
  styleUrl: './agent-profiles.scss',
})
export class AgentProfilesPage {
  protected readonly providers = inject(ProviderStore);
  protected readonly templates = inject(TemplateStore);
  private readonly router = inject(Router);
  protected readonly ago = ago;

  /** The blueprint the editor is open on. A fresh object each time, so the panel
   *  can tell one draft from the next and clear its half-typed rows. */
  protected readonly draft = signal<ProfileDraft>(newProfileDraft());
  protected readonly open = signal(false);   // editor pane visible (mobile + first load)

  protected readonly deployFor = signal<ProfileTemplate | null>(null);

  protected newTemplate(): void {
    this.draft.set(newProfileDraft());
    this.open.set(true);
  }

  protected edit(t: ProfileTemplate): void {
    // a template stores ollama flat; the dropdown lists one option per instance
    const option = providerOptionFor(
      t.provider, t.baseUrl,
      providerOptions(this.providers.llmProviders(), this.providers.ollamaProviders()),
      this.providers.ollamaProviders());
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
    if (!confirm(`Delete template "${t.name}"? This cannot be undone.`)) return;
    await this.templates.remove(t.id);
    if (this.draft().id === t.id) this.closeEditor();
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
