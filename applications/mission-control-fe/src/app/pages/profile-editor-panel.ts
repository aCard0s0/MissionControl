import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { McpCatalogServer, ProfileTemplate, TemplateMcp } from '../core/models';
import { McpEndpointForm } from '../shared/mcp-endpoint-form';
import { StatusDot } from '../shared/status-dot';
import { providerOptions } from '../shared/provider-resolve';
import {
  ProfileDraft, catalogTemplateSnapshot, detachedTemplateMcp, envKeyValid, profileDraftToInput,
  profileDraftValid, skillIdValid,
} from './profile-editor';

/**
 * The blueprint form: identity, the two files, and the three repeating lists a
 * profile carries — skills, MCP servers and encrypted keys.
 *
 * The page decides which draft exists (new, or loaded from a stored template);
 * this owns everything about editing it, including the scratch fields the "add"
 * rows type into. Those reset whenever a different draft arrives, which is why
 * the page hands over a fresh object each time rather than mutating one in place.
 *
 * What a draft is and what the backend gets are in ./profile-editor, so what is
 * left here is the form's own bookkeeping.
 */
@Component({
  selector: 'mc-profile-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot],
  templateUrl: './profile-editor-panel.html',
  styleUrl: './profile-editor-panel.scss',
})
export class ProfileEditorPanel {
  /** The blueprint on screen. The form binds straight into it. */
  readonly draft = input.required<ProfileDraft>();

  /** The id the backend answered with, once a save lands. */
  readonly saved = output<string>();
  readonly closed = output<void>();
  readonly deployRequested = output<ProfileTemplate>();
  readonly removeRequested = output<ProfileTemplate>();

  private readonly store = inject(HermesStore);
  protected readonly catalog = this.store.mcpServers;
  protected readonly saving = signal(false);

  protected readonly providers = computed(() =>
    providerOptions(this.store.llmProviders(), this.store.modelProviders()));

  // add-row scratch fields
  protected newSkill = '';
  /** custom-definition form — templates most often carry a stdio command */
  protected readonly mcpForm = new McpEndpointForm('stdio');
  protected mcpCatalogId = '';
  protected mcpCatalogAlias = '';
  protected secretKey = '';
  protected secretValue = '';

  constructor() {
    // a different draft means a different blueprint (or a fresh one): half-typed
    // rows from the last one must not carry over
    effect(() => {
      this.draft();
      untracked(() => this.resetScratch());
    });
  }

  /** The stored template being edited, or null while a new one is authored. */
  protected stored(): ProfileTemplate | null {
    return this.store.templateById(this.draft().id);
  }

  protected title(): string {
    const stored = this.stored();
    return stored ? `edit — ${stored.name}` : 'new profile template';
  }

  /** The catalog entry the snapshot picker is pointed at. */
  protected catalogServer(): McpCatalogServer | null {
    return this.store.mcpServerById(this.mcpCatalogId);
  }

  // ── skills ──────────────────────────────────────────────────────────────
  protected addSkill(): void {
    const skill = this.newSkill.trim();
    if (!skill) { this.newSkill = ''; return; }
    if (!skillIdValid(skill)) {
      this.store.toast(`invalid skill id "${skill}" — use letters, digits, . _ - (no spaces)`);
      return;
    }
    const draft = this.draft();
    if (!draft.skills.includes(skill)) draft.skills.push(skill);
    this.newSkill = '';
  }

  protected removeSkill(skill: string): void {
    const draft = this.draft();
    draft.skills = draft.skills.filter(s => s !== skill);
  }

  // ── mcp servers ─────────────────────────────────────────────────────────
  protected addMcp(): void {
    const endpoint = this.mcpForm.endpoint();
    if (!endpoint) return;
    const server: TemplateMcp = {
      name: this.mcpForm.trimmedName(), transport: this.mcpForm.transport, enabled: true,
      ...endpoint,
    };
    const draft = this.draft();
    draft.mcpServers = [...draft.mcpServers.filter(m => m.name !== server.name), server];
    this.mcpForm.reset();
  }

  protected removeMcp(name: string): void {
    const draft = this.draft();
    draft.mcpServers = draft.mcpServers.filter(m => m.name !== name);
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
    if (this.draft().mcpServers.some(item => item.name === alias)) {
      this.store.toast(`an MCP server named "${alias}" is already in this template`);
      return;
    }
    const snapshot = catalogTemplateSnapshot(server, alias);
    if (!snapshot) {
      this.store.toast(`${server.name} does not have a usable connection definition`);
      return;
    }
    this.draft().mcpServers.push(snapshot);
    this.mcpCatalogId = this.mcpCatalogAlias = '';
  }

  // ── keys ────────────────────────────────────────────────────────────────
  protected addSecret(): void {
    const key = this.secretKey.trim().toUpperCase();
    if (!key || !envKeyValid(key)) return;
    const value = this.secretValue;
    const draft = this.draft();
    draft.secrets = [
      ...draft.secrets.filter(s => s.key !== key),
      { key, value, set: !!value, recoverable: !!value },
    ];
    this.secretKey = this.secretValue = '';
  }

  protected removeSecret(key: string): void {
    const draft = this.draft();
    draft.secrets = draft.secrets.filter(s => s.key !== key);
  }

  // ── save ────────────────────────────────────────────────────────────────
  protected canSave(): boolean {
    return profileDraftValid(this.draft());
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    if (!this.canSave() || this.saving()) return;
    this.saving.set(true);
    const id = await this.store.saveTemplate(
      profileDraftToInput(draft, this.store.modelProviders()), draft.id ?? undefined);
    this.saving.set(false);
    if (!id) return;
    draft.id = id;
    // The source id is request-only. Reload the backend-materialized shape so a
    // second save in the same open editor cannot re-read a changed catalog record.
    // Mock mode may retain structural extras, so copy only public TemplateMcp
    // fields explicitly.
    const saved = this.store.templateById(id);
    draft.mcpServers = (saved?.mcpServers ?? draft.mcpServers).map(detachedTemplateMcp);
    this.saved.emit(id);
  }

  private resetScratch(): void {
    this.newSkill = '';
    this.mcpForm.reset();
    this.mcpCatalogId = this.mcpCatalogAlias = '';
    this.secretKey = this.secretValue = '';
  }
}
