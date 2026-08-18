import { SkillContent, SkillRef } from '../models';
import { seedSkillBodies } from '../mock-data';
import { AgentStore } from './agent-store';
import { LogStore } from './log-store';
import { StoreContext, nid } from './store-context';

/** The skills installed in one profile, and their SKILL.md bodies. */
export class AgentSkillStore {
  /** mock-mode SKILL.md store; edits persist in-session over the seeded bodies */
  private readonly mockBodies: Record<string, string>;

  constructor(
    private readonly ctx: StoreContext,
    private readonly agents: AgentStore,
    private readonly logs: LogStore,
  ) {
    this.mockBodies = ctx.mock ? seedSkillBodies() : {};
  }

  toggle(agentId: string, skillId: string): void {
    const agent = this.agents.byId(agentId);
    const skill = agent?.skills.find(s => s.id === skillId);
    if (!agent || !skill) return;
    if (!this.ctx.mock) {
      void this.agents.mutate(agentId, 'skill update',
        ref => this.ctx.api.agents.skills.setEnabled(ref, skill.name, !skill.enabled));
      return;
    }
    this.agents.update(agentId, x => ({
      ...x, skills: x.skills.map(s => s.id === skillId ? { ...s, enabled: !s.enabled } : s),
    }));
  }

  add(agentId: string, skill: Omit<SkillRef, 'id'>): void {
    if (!this.agents.byId(agentId)) return;
    if (!this.ctx.mock) {
      void this.agents.mutate(agentId, 'skill install',
        ref => this.ctx.api.agents.skills.install(ref, skill.name));
      return;
    }
    this.agents.update(agentId, x => ({
      ...x, skills: [...x.skills, { ...skill, id: nid('s') }],
    }));
  }

  remove(agentId: string, skillId: string): void {
    const agent = this.agents.byId(agentId);
    const skill = agent?.skills.find(s => s.id === skillId);
    if (!agent || !skill) return;
    if (!this.ctx.mock) {
      void this.agents.mutate(agentId, 'skill uninstall',
        ref => this.ctx.api.agents.skills.uninstall(ref, skill.name));
      return;
    }
    this.agents.update(agentId, x => ({
      ...x, skills: x.skills.filter(s => s.id !== skillId),
    }));
  }

  /** Load a skill's SKILL.md body + file list for the explore/edit viewer. */
  async content(agentId: string, skill: SkillRef): Promise<SkillContent | null> {
    const agent = this.agents.byId(agentId);
    if (!agent) return null;
    if (!this.ctx.mock) {
      const resolved = this.agents.resolve(agentId);
      if (!resolved) return null;
      try {
        const c = await this.ctx.api.agents.skills.content(resolved.ref, skill.name);
        return { name: c.name, path: c.path, body: c.body, files: c.files ?? [] };
      } catch (e) {
        this.ctx.toastFailure('load skill', e);
        return null;
      }
    }
    return {
      name: skill.name,
      path: `~/.hermes/profiles/${agent.name}/skills/${skill.name}`,
      body: this.mockBodies[skill.name] ?? synthSkillBody(skill),
      files: ['SKILL.md'],
    };
  }

  /** Persist an edited SKILL.md. Returns true on success. */
  async saveContent(agentId: string, skill: SkillRef, body: string): Promise<boolean> {
    const agent = this.agents.byId(agentId);
    if (!agent) return false;
    if (!this.ctx.mock) {
      return this.agents.mutate(agentId, 'save skill',
        ref => this.ctx.api.agents.skills.updateContent(ref, skill.name, body));
    }
    this.mockBodies[skill.name] = body;
    this.logs.append(agent.containerId, {
      ts: Date.now(), level: 'info', source: 'system', agentId,
      msg: `skill ${skill.name} SKILL.md updated via dashboard`,
    });
    return true;
  }
}

/** Fallback SKILL.md when no seeded body exists for a mock skill. */
function synthSkillBody(skill: SkillRef): string {
  return `---\nname: ${skill.name}\ndescription: ${skill.description}\nversion: ${skill.version}\nsource: ${skill.source}\n---\n\n# ${skill.name}\n\n${skill.description}\n`;
}
