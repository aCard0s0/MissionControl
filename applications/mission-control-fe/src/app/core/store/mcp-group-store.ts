import { Injectable } from '@angular/core';
import { AgentRef } from '../api/agent-ref';
import { ApiMcpGroup } from '../hermes-api';
import { DeployedPart, McpGroup, McpGroupInput } from '../models';
import { byName, LibraryStore } from './library-store';
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
export class McpGroupStore extends LibraryStore<McpGroup, ApiMcpGroup, McpGroupInput> {
  readonly groups = this.items;

  protected readonly noun = 'MCP group';
  protected readonly toModel = toMcpGroup;
  protected override readonly order = byName;

  protected wire() {
    return this.ctx.api.mcpGroups;
  }

  /**
   * Connects a whole group to one agent.
   *
   * Answers the per-part report, or null when the request itself failed. A group that
   * half-landed still returns its report — the caller renders it rather than treating a
   * partial connect as a plain success or a plain failure.
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
}
