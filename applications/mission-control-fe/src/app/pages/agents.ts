import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { AgentProfile } from '../core/models';
import { agentSessionCommand } from '../core/hermes-commands';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { AgentCreateDialog } from './agent-create-dialog';

/**
 * The profile roster for the selected container: today's totals, one card each,
 * and a shell shortcut into the agent's own session. Creating one is its own
 * dialog — see {@link AgentCreateDialog}.
 */
@Component({
  selector: 'mc-agents',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, StatusDot, RollingNumber, Reveal, AgentCreateDialog],
  templateUrl: './agents.html',
  styleUrl: './agents.scss',
})
export class AgentsPage {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  protected readonly terminal = inject(TerminalRequestStore);
  private readonly router = inject(Router);

  protected readonly ago = ago;
  protected readonly createOpen = signal(false);

  protected readonly agentCommand = agentSessionCommand;

  protected readonly totals = computed(() => {
    const as = this.agents.forSelectedContainer();
    return {
      msgs: as.reduce((s, a) => s + a.msgsToday, 0),
      tokens: as.reduce((s, a) => s + a.tokensToday, 0),
      active: as.filter(a => a.state === 'active').length,
    };
  });

  protected openCreate(): void {
    this.createOpen.set(true);
  }

  /** Straight to the new profile — creating one is the start of configuring it. */
  protected onCreated(id: string): void {
    this.createOpen.set(false);
    this.router.navigate(['/agents', id]);
  }

  /** Open the terminal panel on a shell already running this agent. */
  protected openShell(a: AgentProfile): void {
    const c = this.containers.containers().find(x => x.id === a.containerId);
    if (c) this.terminal.openAgentShell(a, c);
  }

  protected upIntegrations(agentId: string): string[] {
    const a = this.agents.byId(agentId);
    return a ? a.integrations.filter(i => i.status === 'up' || i.status === 'degraded').map(i => i.kind) : [];
  }
}
