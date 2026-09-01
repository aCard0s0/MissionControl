import { inject, Injectable, signal, WritableSignal } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { DeployedPart, McpGroup, McpGroupInput } from '../models';
import { StoreContext } from './store-context';
import { toDeployedPart, toMcpGroup } from './wire-mappers';

/**
 * The MCP groups — sets of catalog entries, deployable onto an agent in one action.
 *
 * Unlike the skill and prompt group slices this one deploys, so it answers a per-part report
 * rather than a boolean: a group names servers that can go missing between the composing and
 * the connecting, and an agent may already hold half of them.
 *
 * A deploy is followed by a refresh, because the agent coverage every group carries is derived
 * from the links the deploy just wrote — without it the page would show the counts from before.
 */
@Injectable({ providedIn: 'root' })
export class McpGroupStore {
  readonly groups: WritableSignal<McpGroup[]> = signal([]);

  private readonly ctx = inject(StoreContext);


  async refresh(): Promise<void> {
    try {
      this.groups.set((await this.ctx.api.mcpGroups.list()).map(toMcpGroup));
    } catch { /* transient backend hiccup — keep last known state */ }
  }

  /** Create (no id) or update (id). Returns the id, or '' on failure. */
  async save(input: McpGroupInput, id?: string): Promise<string> {
    try {
      const saved = id
        ? await this.ctx.api.mcpGroups.update(id, input)
        : await this.ctx.api.mcpGroups.create(input);
      this.upsert(toMcpGroup(saved));
      return saved.id;
    } catch (e) {
      this.ctx.toastFailure('save MCP group', e);
      return '';
    }
  }

  async remove(id: string): Promise<boolean> {
    try {
      await this.ctx.api.mcpGroups.remove(id);
    } catch (e) {
      this.ctx.toastFailure('delete MCP group', e);
      return false;
    }
    this.groups.update(gs => gs.filter(g => g.id !== id));
    return true;
  }

  /**
   * Connects a whole group to one agent.
   *
   * Answers the per-part report, or null when the request itself failed. A group that
   * half-landed still returns its report — the caller renders it rather than treating a partial
   * connect as a plain success or a plain failure.
   */
  async deploy(id: string, agent: AgentRef): Promise<DeployedPart[] | null> {
    let parts: DeployedPart[];
    try {
      const result = await this.ctx.api.mcpGroups.deploy(id, agent);
      parts = (result.parts ?? []).map(toDeployedPart);
    } catch (e) {
      this.ctx.toastFailure('deploy MCP group', e);
      return null;
    }
    // the coverage counts are derived from the links this just wrote
    await this.refresh();
    return parts;
  }

  /** Kept by name, the order the backend reads them in: these are headers, so a save must not
   *  move them. */
  private upsert(group: McpGroup): void {
    this.groups.update(gs => [
      ...gs.filter(g => g.id !== group.id), group,
    ].sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })));
  }
}
