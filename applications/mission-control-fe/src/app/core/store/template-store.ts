import { computed, inject, Injectable } from '@angular/core';
import { ApiProfileTemplate } from '../hermes-api';
import { ProfileTemplate, ProfileTemplateInput } from '../models';
import { AgentStore } from './agent-store';
import { ContainerStore } from './container-store';
import { LibraryStore } from './library-store';
import { toProfileTemplate } from './wire-mappers';

/** Reusable agent blueprints — global, not scoped to a container. */
@Injectable({ providedIn: 'root' })
export class TemplateStore
  extends LibraryStore<ProfileTemplate, ApiProfileTemplate, ProfileTemplateInput> {
  readonly templates = this.items;

  /** Every category in use, for the page's filter chips. */
  readonly categories = computed(() =>
    [...new Set(this.templates().map(t => t.category).filter(Boolean))].sort());

  protected readonly noun = 'template';
  protected readonly toModel = toProfileTemplate;

  private readonly containers = inject(ContainerStore);
  private readonly agents = inject(AgentStore);

  protected wire() {
    return this.ctx.api.templates;
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
}
