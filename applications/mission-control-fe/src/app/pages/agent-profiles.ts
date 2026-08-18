import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { ProfileTemplate, TemplateMcp } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { McpEndpointForm } from '../shared/mcp-endpoint-form';
import { providerOptionFor, providerOptions } from '../shared/provider-resolve';
import { ProfileDeployDialog } from './profile-deploy-dialog';
import {
  ProfileDraft, catalogTemplateSnapshot, detachedTemplateMcp, envKeyValid, newProfileDraft,
  profileDraftFrom, profileDraftToInput, profileDraftValid, skillIdValid,
} from './profile-editor';

/**
 * Agent Profiles — author reusable blueprints (soul, memory, skills, MCP servers,
 * encrypted keys) that can be applied when deploying an agent (see the Agents
 * page "from profile" selector).
 *
 * The page is the list plus the editor's own bookkeeping: what a draft is, what
 * makes it saveable and what the backend gets lives in ./profile-editor, and
 * deploying one is its own dialog.
 */
@Component({
  selector: 'mc-agent-profiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, Reveal, ProfileDeployDialog],
  templateUrl: './agent-profiles.html',
  styleUrl: './agent-profiles.scss',
})
export class AgentProfilesPage {
  protected readonly store = inject(HermesStore);
  private readonly router = inject(Router);
  protected readonly ago = ago;

  protected readonly providers = computed(() =>
    providerOptions(this.store.llmProviders(), this.store.modelProviders()));

  // ── editor state ──────────────────────────────────────────────────────────
  /** The template on the editor pane. Bound in place by the form's own fields. */
  protected draft: ProfileDraft = newProfileDraft();
  protected readonly open = signal(false);   // editor pane visible (mobile + first load)
  protected readonly saving = signal(false);

  // add-row scratch fields
  protected newSkill = '';
  /** custom-definition form — templates most often carry a stdio command */
  protected readonly mcpForm = new McpEndpointForm('stdio');
  protected mcpCatalogId = '';
  protected mcpCatalogAlias = '';
  protected secretKey = '';
  protected secretValue = '';

  /** The stored template being edited, or null while a new one is being authored. */
  protected editing(): ProfileTemplate | null {
    return this.store.templateById(this.draft.id);
  }

  // ── deploy ──────────────────────────────────────────────────────────────────
  protected readonly deployFor = signal<ProfileTemplate | null>(null);

  protected openDeploy(t: ProfileTemplate): void {
    this.deployFor.set(t);
  }

  /** Straight to the agent the blueprint just became. */
  protected onDeployed(agentId: string): void {
    this.deployFor.set(null);
    this.router.navigate(['/agents', agentId]);
  }

  // ── list actions ────────────────────────────────────────────────────────────
  protected newTemplate(): void {
    this.draft = newProfileDraft();
    this.resetScratch();
    this.open.set(true);
  }

  protected edit(t: ProfileTemplate): void {
    // a template stores ollama flat; the dropdown lists one option per instance
    const option = providerOptionFor(
      t.provider, t.baseUrl, this.providers(), this.store.modelProviders());
    this.draft = profileDraftFrom(t, option ?? (t.provider || 'nous'));
    this.resetScratch();
    this.open.set(true);
  }

  protected async remove(t: ProfileTemplate): Promise<void> {
    if (!confirm(`Delete template "${t.name}"? This cannot be undone.`)) return;
    await this.store.deleteTemplate(t.id);
    if (this.draft.id === t.id) this.closeEditor();
  }

  protected closeEditor(): void {
    this.open.set(false);
    this.draft = newProfileDraft();
  }

  // ── skills ──────────────────────────────────────────────────────────────────
  protected addSkill(): void {
    const skill = this.newSkill.trim();
    if (!skill) { this.newSkill = ''; return; }
    if (!skillIdValid(skill)) {
      this.store.toast(`invalid skill id "${skill}" — use letters, digits, . _ - (no spaces)`);
      return;
    }
    if (!this.draft.skills.includes(skill)) this.draft.skills.push(skill);
    this.newSkill = '';
  }

  protected removeSkill(skill: string): void {
    this.draft.skills = this.draft.skills.filter(s => s !== skill);
  }

  // ── mcp servers ───────────────────────────────────────────────────────────────
  protected addMcp(): void {
    const endpoint = this.mcpForm.endpoint();
    if (!endpoint) return;
    const server: TemplateMcp = {
      name: this.mcpForm.trimmedName(), transport: this.mcpForm.transport, enabled: true,
      ...endpoint,
    };
    this.draft.mcpServers = [
      ...this.draft.mcpServers.filter(m => m.name !== server.name), server,
    ];
    this.mcpForm.reset();
  }

  protected removeMcp(name: string): void {
    this.draft.mcpServers = this.draft.mcpServers.filter(m => m.name !== name);
  }

  protected selectCatalogMcp(id: string): void {
    this.mcpCatalogId = id;
    const server = this.store.mcpServerById(id);
    if (server) this.mcpCatalogAlias = server.name;
  }

  protected addCatalogMcp(): void {
    const server = this.store.mcpServerById(this.mcpCatalogId);
    const alias = this.mcpCatalogAlias.trim();
    if (!server || !alias) return;
    if (this.draft.mcpServers.some(item => item.name === alias)) {
      this.store.toast(`an MCP server named "${alias}" is already in this template`);
      return;
    }
    const snapshot = catalogTemplateSnapshot(server, alias);
    if (!snapshot) {
      this.store.toast(`${server.name} does not have a usable connection definition`);
      return;
    }
    this.draft.mcpServers.push(snapshot);
    this.mcpCatalogId = this.mcpCatalogAlias = '';
  }

  // ── secrets ───────────────────────────────────────────────────────────────────
  protected addSecret(): void {
    const key = this.secretKey.trim().toUpperCase();
    if (!key || !envKeyValid(key)) return;
    const value = this.secretValue;
    this.draft.secrets = [
      ...this.draft.secrets.filter(s => s.key !== key),
      { key, value, set: !!value, recoverable: !!value },
    ];
    this.secretKey = this.secretValue = '';
  }

  protected removeSecret(key: string): void {
    this.draft.secrets = this.draft.secrets.filter(s => s.key !== key);
  }

  // ── save ──────────────────────────────────────────────────────────────────────
  protected canSave(): boolean {
    return profileDraftValid(this.draft);
  }

  protected async save(): Promise<void> {
    if (!this.canSave() || this.saving()) return;
    const input = profileDraftToInput(this.draft, this.store.modelProviders());
    this.saving.set(true);
    const id = await this.store.saveTemplate(input, this.draft.id ?? undefined);
    this.saving.set(false);
    if (!id) return;
    this.draft.id = id;
    // The source id is request-only. Reload the backend-materialized shape so a
    // second save in the same open editor cannot re-read a changed catalog record.
    // Mock mode may retain structural extras, so copy only public TemplateMcp
    // fields explicitly.
    const saved = this.store.templateById(id);
    this.draft.mcpServers = (saved?.mcpServers ?? this.draft.mcpServers).map(detachedTemplateMcp);
  }

  private resetScratch(): void {
    this.newSkill = '';
    this.mcpForm.reset();
    this.mcpCatalogId = this.mcpCatalogAlias = '';
    this.secretKey = this.secretValue = '';
  }
}
