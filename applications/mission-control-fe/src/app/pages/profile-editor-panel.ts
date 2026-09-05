import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { InferenceEndpointStore } from '../core/store/inference-endpoint-store';
import { ProviderStore } from '../core/store/provider-store';
import { StoreContext } from '../core/store/store-context';
import { CredentialStore } from '../core/store/credential-store';
import { TemplateStore } from '../core/store/template-store';
import { McpCatalogServer, ProfileTemplate, TemplateMcp } from '../core/models';
import { AGENT_ICONS, AgentIconView } from '../shared/agent-icon';
import { McpEndpointForm } from '../shared/mcp-endpoint-form';
import { ModelPicker, modelCatalogFor } from '../shared/model-picker';
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
  imports: [FormsModule, RouterLink, AgentIconView, StatusDot],
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

  protected readonly catalog = inject(McpCatalogStore);
  private readonly ctx = inject(StoreContext);
  private readonly providers = inject(ProviderStore);
  private readonly endpoints = inject(InferenceEndpointStore);
  private readonly templates = inject(TemplateStore);
  private readonly credentials = inject(CredentialStore);
  protected readonly saving = signal(false);

  protected readonly icons = AGENT_ICONS;

  /** The glyph grid, closed until asked for — twelve buttons above the form would
   *  outweigh the field they belong to. */
  protected readonly iconOpen = signal(false);

  /**
   * Which optional groups are open.
   *
   * <p>All closed to begin with. The form carries eleven fields and three
   * repeating lists, and an operator renaming a blueprint had to scroll past a
   * SOUL.md textarea to reach the save button. What each group holds is on its
   * own header instead, so opening one is a decision rather than a search.
   */
  private readonly openGroups = signal<ReadonlySet<string>>(new Set());

  protected readonly providerChoices = computed(() =>
    providerOptions(this.providers.llmProviders(), this.endpoints.endpoints()));

  /** The model field and the suggestions behind it: the chosen provider's catalog, or an
   *  endpoint's installed models. It holds the model while editing; `save` copies it back
   *  onto the draft, the same way the create dialog reads its picker on submit. */
  protected readonly picker = new ModelPicker();

  /** Categories already in use, offered as a datalist so the library stays tidy
   *  without turning the field into a fixed list. */
  protected readonly templateCategories = computed(() => this.templates.categories());

  // add-row scratch fields
  protected newSkill = '';
  /** custom-definition form — templates most often carry a stdio command */
  protected readonly mcpForm = new McpEndpointForm('stdio');
  protected mcpCatalogId = '';
  protected mcpCatalogAlias = '';
  protected secretKey = '';
  protected secretValue = '';
  /** A saved credential picked for the row being added, or '' for a typed value. */
  protected secretCredentialId = '';

  constructor() {
    // a different draft means a different blueprint (or a fresh one): half-typed
    // rows from the last one must not carry over
    effect(() => {
      const form = this.draft();
      untracked(() => {
        this.resetScratch();
        // the groups close with it — otherwise the next blueprint opens on
        // whatever the last one happened to have expanded
        this.openGroups.set(new Set());
        this.iconOpen.set(false);
        // show the stored model at once; the catalog load keeps it as `preferred`, so a
        // model the list does not carry (a fine-tune, a typed id) is not replaced
        this.picker.model = form.model;
        void this.picker.load(this.catalogFor(form.provider), { preferred: form.model });
      });
    });
  }

  // ── provider & model ────────────────────────────────────────────────────
  protected onProvider(option: string): void {
    this.draft().provider = option;
    this.picker.model = '';   // drop the prior provider's model; the load re-selects
    void this.picker.load(this.catalogFor(option));
  }

  private catalogFor(option: string) {
    return modelCatalogFor(option, this.providers, this.endpoints);
  }

  // ── icon ────────────────────────────────────────────────────────────────
  protected pickIcon(icon: string): void {
    // picking the one already set clears it, so the default is reachable without
    // a thirteenth button that means "none"
    this.draft().icon = this.draft().icon === icon ? '' : icon;
    this.iconOpen.set(false);
  }

  // ── optional groups ─────────────────────────────────────────────────────
  protected groupOpen(key: string): boolean {
    return this.openGroups().has(key);
  }

  protected toggleGroup(key: string): void {
    this.openGroups.update(open => {
      const next = new Set(open);
      if (!next.delete(key)) next.add(key);
      return next;
    });
  }

  /** What a closed group holds, so its header answers whether to open it. */
  protected groupNote(key: string): string {
    const form = this.draft();
    switch (key) {
      case 'endpoint': return form.baseUrl.trim() || form.cwd.trim() || 'defaults';
      case 'soul': return form.soul.trim() ? `${lines(form.soul)} lines` : 'empty';
      case 'memory': return form.memory.trim() ? `${lines(form.memory)} lines` : 'empty';
      case 'skills': return count(form.skills.length, 'skill');
      case 'mcp': return count(form.mcpServers.length, 'server');
      case 'keys': return count(form.secrets.length, 'key');
      default: return '';
    }
  }

  /** The stored template being edited, or null while a new one is authored. */
  protected stored(): ProfileTemplate | null {
    return this.templates.byId(this.draft().id);
  }

  protected title(): string {
    const stored = this.stored();
    return stored ? `edit — ${stored.name}` : 'new profile template';
  }

  /** The catalog entry the snapshot picker is pointed at. */
  protected catalogServer(): McpCatalogServer | null {
    return this.catalog.byId(this.mcpCatalogId);
  }

  // ── skills ──────────────────────────────────────────────────────────────
  protected addSkill(): void {
    const skill = this.newSkill.trim();
    if (!skill) { this.newSkill = ''; return; }
    if (!skillIdValid(skill)) {
      this.ctx.toast(`invalid skill id "${skill}" — use letters, digits, . _ - (no spaces)`);
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
    const server = this.catalog.byId(id);
    if (server) this.mcpCatalogAlias = server.name;
  }

  protected addCatalogMcp(): void {
    const server = this.catalog.byId(this.mcpCatalogId);
    const alias = this.mcpCatalogAlias.trim();
    if (!server || !alias) return;
    if (this.draft().mcpServers.some(item => item.name === alias)) {
      this.ctx.toast(`an MCP server named "${alias}" is already in this template`);
      return;
    }
    const snapshot = catalogTemplateSnapshot(server, alias);
    if (!snapshot) {
      this.ctx.toast(`${server.name} does not have a usable connection definition`);
      return;
    }
    this.draft().mcpServers.push(snapshot);
    this.mcpCatalogId = this.mcpCatalogAlias = '';
  }

  // ── keys ────────────────────────────────────────────────────────────────

  /**
   * Every secret a saved credential holds, as one flat list of options.
   *
   * Flattened rather than nested because of how an operator arrives here: they remember "the
   * production Anthropic key", not `ANTHROPIC_API_KEY`. Picking an option fills the variable
   * name too, so the credential is the thing chosen and the key falls out of it.
   *
   * Plain entries are left out — a blueprint's keys list is its secrets, and a home channel
   * belongs in the profile's config rather than here.
   */
  protected credentialKeys(): Array<{ id: string; key: string; label: string }> {
    return this.credentials.credentials().flatMap(c =>
      c.entries.filter(e => e.secret && e.set && e.recoverable)
        .map(e => ({ id: c.id, key: e.key, label: `${c.name} · ${e.key}` })));
  }

  /** Picking a credential names the variable as well, and clears the typed value: the two are
   *  alternatives, and the backend copies the credential's envelope rather than any value. */
  protected pickSecretCredential(option: string): void {
    const [id, key] = option ? option.split('\u0000') : ['', ''];
    this.secretCredentialId = id;
    if (id) {
      this.secretKey = key;
      this.secretValue = '';
    }
  }

  protected addSecret(): void {
    const key = this.secretKey.trim().toUpperCase();
    if (!key || !envKeyValid(key)) return;
    const credentialId = this.secretCredentialId;
    const value = credentialId ? '' : this.secretValue;
    const draft = this.draft();
    draft.secrets = [
      ...draft.secrets.filter(s => s.key !== key),
      credentialId
        // set/recoverable are true because the backend refuses a credential it cannot open
        ? { key, value: '', set: true, recoverable: true, credentialId }
        : { key, value, set: !!value, recoverable: !!value },
    ];
    this.secretKey = this.secretValue = this.secretCredentialId = '';
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
    draft.model = this.picker.model;
    const id = await this.templates.save(
      profileDraftToInput(draft, this.endpoints.endpoints()), draft.id ?? undefined);
    this.saving.set(false);
    if (!id) return;
    draft.id = id;
    // The source id is request-only. Reload the backend-materialized shape so a
    // second save in the same open editor cannot re-read a changed catalog record,
    // copying only the public TemplateMcp fields.
    const saved = this.templates.byId(id);
    draft.mcpServers = (saved?.mcpServers ?? draft.mcpServers).map(detachedTemplateMcp);
    this.saved.emit(id);
  }

  private resetScratch(): void {
    this.newSkill = '';
    this.mcpForm.reset();
    this.mcpCatalogId = this.mcpCatalogAlias = '';
    this.secretKey = this.secretValue = this.secretCredentialId = '';
  }
}

/** `n thing` / `n things`, or the word 'none' — a count of 0 reads badly as '0 skills'. */
function count(n: number, noun: string): string {
  return n === 0 ? 'none' : `${n} ${noun}${n === 1 ? '' : 's'}`;
}

function lines(text: string): number {
  return text.trim().split('\n').length;
}
