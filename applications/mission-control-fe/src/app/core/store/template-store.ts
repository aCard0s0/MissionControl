import { WritableSignal, signal } from '@angular/core';
import { ProfileTemplate, ProfileTemplateInput } from '../models';
import { seedTemplates } from '../mock-data';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { ProviderStore } from './provider-store';
import { StoreContext, nid } from './store-context';
import { toProfileTemplate } from './wire-mappers';

/** Reusable agent blueprints — global, not scoped to a container. */
export class TemplateStore {
  readonly templates: WritableSignal<ProfileTemplate[]>;

  constructor(
    private readonly ctx: StoreContext,
    private readonly containers: ContainerStore,
    private readonly agents: AgentStore,
    private readonly providers: ProviderStore,
  ) {
    this.templates = signal(ctx.mock ? seedTemplates() : []);
    // mock profile creation seeds files from a template; wiring it here keeps
    // AgentStore unaware of this slice
    agents.templateSource = id => this.byId(id);
  }

  byId = (id: string | null): ProfileTemplate | null =>
    this.templates().find(t => t.id === id) ?? null;

  async refresh(): Promise<void> {
    if (this.ctx.mock) return;
    try {
      this.templates.set((await this.ctx.api.templates.list()).map(toProfileTemplate));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id) a template. Returns the id, or '' on failure. */
  async save(input: ProfileTemplateInput, id?: string): Promise<string> {
    if (!this.ctx.mock) {
      try {
        const saved = id
          ? await this.ctx.api.templates.update(id, input)
          : await this.ctx.api.templates.create(input);
        this.upsert(toProfileTemplate(saved));
        return saved.id;
      } catch (e) {
        this.ctx.toastFailure('save template', e);
        return '';
      }
    }
    return this.saveMock(input, id);
  }

  async remove(id: string): Promise<void> {
    if (!this.ctx.mock) {
      try {
        await this.ctx.api.templates.remove(id);
      } catch (e) {
        this.ctx.toastFailure('delete template', e);
        return;
      }
    }
    this.templates.update(ts => ts.filter(t => t.id !== id));
  }

  /** Deploy a template into a container as a new agent. Returns the agent id, or ''. */
  async deploy(templateId: string, containerId: string, name: string): Promise<string> {
    const template = this.byId(templateId);
    if (!template) return '';
    if (!this.ctx.mock) {
      const container = this.containers.byId(containerId);
      if (!container) return '';
      try {
        const created = await this.ctx.api.templates.deploy(templateId, {
          hostId: container.hostId, containerId, name,
        });
        return this.agents.adopt(created);
      } catch (e) {
        this.ctx.toastFailure('deploy template', e);
        return '';
      }
    }
    return this.agents.create(
      containerId, name, template.provider || 'anthropic', template.model || 'claude-fable-5',
      '', undefined, template.baseUrl || undefined, templateId);
  }

  /** Snapshot a running agent's config into a new template. Returns the template id. */
  async capture(agentId: string, templateName?: string): Promise<string> {
    const resolved = this.agents.resolve(agentId);
    const agent = this.agents.byId(agentId);
    if (!agent) return '';
    if (!this.ctx.mock) {
      if (!resolved) return '';
      try {
        const t = await this.ctx.api.templates.capture(resolved.ref, templateName);
        this.upsert(toProfileTemplate(t));
        return t.id;
      } catch (e) {
        this.ctx.toastFailure('capture template', e);
        return '';
      }
    }
    const now = Date.now();
    // mirror the backend capture: raw .env values can't be read back, so we record
    // which provider key was set (by its real env var, not a hardcoded one) as an
    // unset placeholder the user re-enters before deploy.
    const keyVar = this.providers.llmProviders().find(p => p.key === agent.provider)?.envVar;
    const template: ProfileTemplate = {
      id: nid('pt'), name: templateName?.trim() || `${agent.name}-template`,
      description: `Captured from ${agent.name}`, provider: agent.provider, model: agent.model,
      baseUrl: '', cwd: agent.cwd, soul: agent.soul, memory: agent.memoryMd,
      skills: agent.skills.filter(s => s.enabled).map(s => s.name),
      mcpServers: agent.mcp.map(m => ({
        name: m.name, transport: m.transport, url: m.url, command: m.command, args: m.args,
        enabled: m.status !== 'disabled',
      })),
      secrets: keyVar ? [{ key: keyVar, set: false, recoverable: false }] : [],
      createdAt: now, updatedAt: now,
    };
    this.upsert(template);
    return template.id;
  }

  private upsert(template: ProfileTemplate): void {
    this.templates.update(ts => [template, ...ts.filter(x => x.id !== template.id)]);
  }

  private saveMock(input: ProfileTemplateInput, id?: string): string {
    const now = Date.now();
    const existing = id ? this.byId(id) : null;
    const prior = new Map((existing?.secrets ?? []).map(s => [s.key, s]));
    const secrets = input.secrets
      .filter(s => s.key.trim())
      .map(s => {
        if (s.value) return { key: s.key, set: true, recoverable: true };
        return prior.get(s.key) ?? { key: s.key, set: false, recoverable: false };
      });
    const template: ProfileTemplate = {
      id: id ?? nid('pt'),
      name: input.name, description: input.description, provider: input.provider, model: input.model,
      baseUrl: input.baseUrl, cwd: input.cwd, soul: input.soul, memory: input.memory,
      skills: input.skills.filter(s => s.trim()),
      mcpServers: input.mcpServers.filter(m => m.name.trim()),
      secrets,
      createdAt: existing?.createdAt ?? now, updatedAt: now,
    };
    this.upsert(template);
    return template.id;
  }
}
