import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { ProfileTemplate, ProfileTemplateInput, TemplateMcp } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { OLLAMA_PREFIX, ollamaOptionForBaseUrl } from '../shared/provider-resolve';

interface SecretRow { key: string; value: string; set: boolean; recoverable: boolean; }

/** Skill ids / env keys the backend accepts — mirror PROFILE_NAME / ENV_KEY there
 *  (ENV_KEY caps at 64 chars to match the server, so the editor rejects what a
 *  save would). */
const SKILL_ID = /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/;
const ENV_KEY = /^[A-Z][A-Z0-9_]{1,63}$/;

/**
 * Agent Profiles — author reusable blueprints (soul, memory, skills, MCP servers,
 * encrypted keys) that can be applied when deploying an agent (see the Agents
 * page "from profile" selector).
 */
@Component({
  selector: 'mc-agent-profiles',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, RouterLink, StatusDot, Reveal],
  templateUrl: './agent-profiles.html',
  styleUrl: './agent-profiles.scss',
})
export class AgentProfilesPage {
  protected readonly store = inject(HermesStore);
  private readonly router = inject(Router);
  protected readonly ago = ago;

  protected readonly providerOptions = computed(() => [
    ...this.store.llmProviders().map(p => ({ value: p.key, label: p.label })),
    ...this.store.modelProviders().map(p => ({ value: OLLAMA_PREFIX + p.name, label: 'Ollama: ' + p.name })),
  ]);

  /** Reverse of {@link save}'s ollama flattening: a template stores ollama as a
   *  bare `ollama` + baseUrl, but the editor dropdown lists `ollama: <name>` per
   *  instance — resolve it back (matching on baseUrl, falling back to the first
   *  instance) so the dropdown shows the right option instead of going blank.
   *  Non-ollama providers (or an unknown one) pass through, defaulting to nous. */
  private providerOption(provider: string, baseUrl: string): string {
    if (provider === 'ollama') {
      const opt = ollamaOptionForBaseUrl(baseUrl, this.store.modelProviders());
      if (opt) return opt;
    }
    return provider || 'nous';
  }

  // ── editor state ──────────────────────────────────────────────────────────
  protected readonly editingId = signal<string | null>(null);
  protected readonly open = signal(false);   // editor panel visible (mobile + first load)
  protected readonly saving = signal(false);

  protected name = '';
  protected description = '';
  protected provider = 'nous';
  protected model = 'Hermes-4-405B';
  protected baseUrl = '';
  protected cwd = '/opt/data';
  protected soul = '';
  protected memory = '';

  protected readonly skills = signal<string[]>([]);
  protected readonly mcpServers = signal<TemplateMcp[]>([]);
  protected readonly secrets = signal<SecretRow[]>([]);

  // add-row scratch fields
  protected newSkill = '';
  protected mcpName = '';
  protected mcpTransport: TemplateMcp['transport'] = 'stdio';
  protected mcpUrl = '';
  protected mcpCommand = '';
  protected mcpArgs = '';
  protected secretKey = '';
  protected secretValue = '';

  protected readonly editingName = computed(() => {
    const t = this.store.templateById(this.editingId());
    return t?.name ?? null;
  });

  // ── deploy ──────────────────────────────────────────────────────────────────
  protected readonly deployFor = signal<ProfileTemplate | null>(null);
  protected readonly deploying = signal(false);
  protected deployContainer = '';
  protected deployName = '';

  protected openDeploy(t: ProfileTemplate): void {
    this.deployFor.set(t);
    this.deployName = t.name;
    this.deployContainer = this.store.selectedContainerId() || this.store.containers()[0]?.id || '';
  }

  protected closeDeploy(): void {
    this.deployFor.set(null);
  }

  protected async doDeploy(): Promise<void> {
    const t = this.deployFor();
    const cid = this.deployContainer;
    const name = this.deployName.trim();
    if (!t || !cid || !name || this.deploying()) return;
    // warn before deploying a template that carries no usable key for a provider
    // that needs one (e.g. a captured template) — the agent would fail to auth
    const info = this.store.llmProviders().find(p => p.key === t.provider);
    if (info?.needsKey && info.envVar
        && !t.secrets.some(s => s.key === info.envVar && s.set && s.recoverable)) {
      const ok = confirm(
        `This template has no usable ${info.envVar} for ${info.label}. The deployed agent `
        + `may fail to authenticate until you add the key on its Setup tab. Deploy anyway?`);
      if (!ok) return;
    }
    this.deploying.set(true);
    const id = await this.store.deployTemplate(t.id, cid, name);
    this.deploying.set(false);
    if (id) {
      this.deployFor.set(null);
      this.router.navigate(['/agents', id]);
    }
  }

  // ── list actions ────────────────────────────────────────────────────────────
  protected newTemplate(): void {
    this.editingId.set(null);
    this.name = '';
    this.description = '';
    this.provider = 'nous';
    this.model = 'Hermes-4-405B';
    this.baseUrl = '';
    this.cwd = '/opt/data';
    this.soul = '# SOUL.md\n\nDescribe this agent\'s personality and directives.\n';
    this.memory = '# MEMORY.md\n\n';
    this.skills.set([]);
    this.mcpServers.set([]);
    this.secrets.set([]);
    this.resetScratch();
    this.open.set(true);
  }

  protected edit(t: ProfileTemplate): void {
    this.editingId.set(t.id);
    this.name = t.name;
    this.description = t.description;
    this.provider = this.providerOption(t.provider, t.baseUrl);
    this.model = t.model;
    this.baseUrl = t.baseUrl;
    this.cwd = t.cwd || '/opt/data';
    this.soul = t.soul;
    this.memory = t.memory;
    this.skills.set([...t.skills]);
    this.mcpServers.set(t.mcpServers.map(m => ({ ...m })));
    this.secrets.set(t.secrets.map(s => ({ key: s.key, value: '', set: s.set, recoverable: s.recoverable })));
    this.resetScratch();
    this.open.set(true);
  }

  protected async remove(t: ProfileTemplate): Promise<void> {
    if (!confirm(`Delete template "${t.name}"? This cannot be undone.`)) return;
    await this.store.deleteTemplate(t.id);
    if (this.editingId() === t.id) this.closeEditor();
  }

  protected closeEditor(): void {
    this.open.set(false);
    this.editingId.set(null);
  }

  // ── skills ──────────────────────────────────────────────────────────────────
  protected addSkill(): void {
    const s = this.newSkill.trim();
    if (!s) { this.newSkill = ''; return; }
    // reject ids the backend's installSkill would throw on (a deploy applies skills
    // and rolls the whole agent back on a bad id) — fail here with a clear message
    if (!SKILL_ID.test(s)) {
      this.store.toast(`invalid skill id "${s}" — use letters, digits, . _ - (no spaces)`);
      return;
    }
    if (this.skills().includes(s)) { this.newSkill = ''; return; }
    this.skills.update(list => [...list, s]);
    this.newSkill = '';
  }

  protected removeSkill(skill: string): void {
    this.skills.update(list => list.filter(s => s !== skill));
  }

  // ── mcp servers ───────────────────────────────────────────────────────────────
  protected addMcp(): void {
    const name = this.mcpName.trim();
    if (!name) return;
    const stdio = this.mcpTransport === 'stdio';
    if (stdio && !this.mcpCommand.trim()) return;
    if (!stdio && !this.mcpUrl.trim()) return;
    const server: TemplateMcp = {
      name, transport: this.mcpTransport, enabled: true,
      url: stdio ? undefined : this.mcpUrl.trim(),
      command: stdio ? this.mcpCommand.trim() : undefined,
      args: stdio && this.mcpArgs.trim() ? this.mcpArgs.trim() : undefined,
    };
    this.mcpServers.update(list => [...list.filter(m => m.name !== name), server]);
    this.mcpName = this.mcpUrl = this.mcpCommand = this.mcpArgs = '';
    this.mcpTransport = 'stdio';
  }

  protected removeMcp(name: string): void {
    this.mcpServers.update(list => list.filter(m => m.name !== name));
  }

  // ── secrets ───────────────────────────────────────────────────────────────────
  protected addSecret(): void {
    const key = this.secretKey.trim().toUpperCase();
    if (!key || !ENV_KEY.test(key)) return;
    const value = this.secretValue;
    this.secrets.update(list => [
      ...list.filter(s => s.key !== key),
      { key, value, set: !!value, recoverable: !!value },
    ]);
    this.secretKey = this.secretValue = '';
  }

  protected removeSecret(key: string): void {
    this.secrets.update(list => list.filter(s => s.key !== key));
  }

  // ── save ──────────────────────────────────────────────────────────────────────
  protected canSave(): boolean {
    return !!this.name.trim() && /^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(this.name.trim());
  }

  protected async save(): Promise<void> {
    if (!this.canSave() || this.saving()) return;
    let provider = this.provider;
    let baseUrl = this.baseUrl.trim();
    if (provider.startsWith(OLLAMA_PREFIX)) {
      const op = this.store.modelProviders().find(p => OLLAMA_PREFIX + p.name === provider);
      provider = 'ollama';
      if (op && !baseUrl) baseUrl = op.url.replace(/\/+$/, '') + '/v1';
    }
    const input: ProfileTemplateInput = {
      name: this.name.trim(),
      description: this.description.trim(),
      provider,
      model: this.model.trim(),
      baseUrl,
      cwd: this.cwd.trim(),
      soul: this.soul,
      memory: this.memory,
      skills: this.skills(),
      mcpServers: this.mcpServers(),
      secrets: this.secrets().map(s => ({ key: s.key, value: s.value })),
    };
    this.saving.set(true);
    const id = await this.store.saveTemplate(input, this.editingId() ?? undefined);
    this.saving.set(false);
    if (id) this.editingId.set(id);
  }

  private resetScratch(): void {
    this.newSkill = this.mcpName = this.mcpUrl = this.mcpCommand = this.mcpArgs = '';
    this.secretKey = this.secretValue = '';
    this.mcpTransport = 'stdio';
  }
}
