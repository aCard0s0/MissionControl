import { inject, Injectable } from '@angular/core';
import { SkillContent, SkillRef } from '../models';
import { AgentStore } from './agent-store';
import { StoreContext } from './store-context';

/** The skills installed in one profile, and their SKILL.md bodies. */
@Injectable({ providedIn: 'root' })
export class AgentSkillStore {
  private readonly ctx = inject(StoreContext);
  private readonly agents = inject(AgentStore);

  toggle(agentId: string, skillId: string): void {
    const agent = this.agents.byId(agentId);
    const skill = agent?.skills.find(s => s.id === skillId);
    if (!agent || !skill) {
      this.ctx.gone('skill');
      return;
    }
    void this.agents.mutate(agentId, 'skill update',
      ref => this.ctx.api.agents.skills.setEnabled(ref, skill.name, !skill.enabled));
  }

  add(agentId: string, skill: Omit<SkillRef, 'id'>): void {
    if (!this.agents.byId(agentId)) {
      this.ctx.gone('profile');
      return;
    }
    void this.agents.mutate(agentId, 'skill install',
      ref => this.ctx.api.agents.skills.install(ref, skill.name));
  }

  remove(agentId: string, skillId: string): void {
    const agent = this.agents.byId(agentId);
    const skill = agent?.skills.find(s => s.id === skillId);
    if (!agent || !skill) {
      this.ctx.gone('skill');
      return;
    }
    void this.agents.mutate(agentId, 'skill uninstall',
      ref => this.ctx.api.agents.skills.uninstall(ref, skill.name));
  }

  /** Load a skill's SKILL.md body + file list for the explore/edit viewer. */
  async content(agentId: string, skill: SkillRef): Promise<SkillContent | null> {
    const resolved = this.agents.resolve(agentId);
    if (!resolved) {
      this.ctx.gone('profile');
      return null;
    }
    try {
      const c = await this.ctx.api.agents.skills.content(resolved.ref, skill.name);
      return { name: c.name, path: c.path, body: c.body, files: c.files ?? [] };
    } catch (e) {
      this.ctx.toastFailure('load skill', e);
      return null;
    }
  }

  /** Persist an edited SKILL.md. Returns true on success. */
  async saveContent(agentId: string, skill: SkillRef, body: string): Promise<boolean> {
    if (!this.agents.byId(agentId)) return this.ctx.gone('profile');
    return this.agents.mutate(agentId, 'save skill',
      ref => this.ctx.api.agents.skills.updateContent(ref, skill.name, body));
  }
}
