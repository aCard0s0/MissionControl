import { computed, inject, Injectable, signal, WritableSignal } from '@angular/core';
import { ProfileTemplate, ProfileTemplateInput } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { StoreContext } from './store-context';
import { toProfileTemplate } from './wire-mappers';

/** Reusable agent blueprints — global, not scoped to a container. */
@Injectable({ providedIn: 'root' })
export class TemplateStore {
  readonly templates: WritableSignal<ProfileTemplate[]>;

  /** Every category in use, for the page's filter chips. */
  readonly categories = computed(() =>
    [...new Set(this.templates().map(t => t.category).filter(Boolean))].sort());

  private readonly ctx = inject(StoreContext);
  private readonly containers = inject(ContainerStore);
  private readonly agents = inject(AgentStore);

  constructor() {
    this.templates = signal([]);
  }

  byId = (id: string | null): ProfileTemplate | null =>
    this.templates().find(t => t.id === id) ?? null;

  async refresh(): Promise<void> {
    try {
      this.templates.set((await this.ctx.api.templates.list()).map(toProfileTemplate));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id) a template. Returns the id, or '' on failure. */
  async save(input: ProfileTemplateInput, id?: string): Promise<string> {
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

  async remove(id: string): Promise<void> {
    try {
      await this.ctx.api.templates.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete template', e);
      return;
    }
    this.templates.update(ts => ts.filter(t => t.id !== id));
  }

  /** Deploy a template into a container as a new agent. Returns the agent id, or ''. */
  async deploy(templateId: string, containerId: string, name: string): Promise<string> {
    const template = this.byId(templateId);
    if (!template) {
      this.ctx.gone('template');
      return '';
    }
    const container = this.containers.byId(containerId);
    if (!container) {
      this.ctx.gone('container');
      return '';
    }
    try {
      const created = await this.ctx.api.templates.deploy(templateId, {
        hostId: container.hostId, containerId, name,
      });
      this.ctx.notify(`agent ${name} deployed from ${template.name}`);
      return this.agents.adopt(created);
    } catch (e) {
      this.ctx.toastFailure('deploy template', e);
      return '';
    }
  }

  /** Snapshot a running agent's config into a new template. Returns the template id. */
  async capture(agentId: string, templateName?: string): Promise<string> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return '';
    }
    try {
      const t = await this.ctx.api.templates.capture(resolved.ref, templateName);
      this.upsert(toProfileTemplate(t));
      return t.id;
    } catch (e) {
      this.ctx.toastFailure('capture template', e);
      return '';
    }
  }

  private upsert(template: ProfileTemplate): void {
    this.templates.update(ts => [template, ...ts.filter(x => x.id !== template.id)]);
  }
}
