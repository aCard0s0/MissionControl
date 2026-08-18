import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HermesStore } from '../core/hermes-store';
import { AgentProfile } from '../core/models';
import { StatusDot } from '../shared/status-dot';
import { RollingNumber } from '../shared/rolling-number';
import { Reveal } from '../shared/reveal';
import { ago } from '../core/format';
import { AgentCreateDialog } from './agent-create-dialog';

/**
 * The CLI invocation that drops you into a session with `name`. Hermes takes
 * `-p` only for named profiles — `default` lives at /opt/data and is invoked
 * bare (the same special-case the backend applies in HermesProfiles).
 * Returns undefined for a name that could carry shell metacharacters, which
 * downgrades the shortcut to a plain shell rather than typing it blind.
 */
export function agentSessionCommand(name: string): string | undefined {
  if (!/^[A-Za-z0-9._-]+$/.test(name)) return undefined;
  return name === 'default' ? 'hermes' : `hermes -p ${name}`;
}

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
  protected readonly store = inject(HermesStore);
  private readonly router = inject(Router);

  protected readonly ago = ago;
  protected readonly createOpen = signal(false);

  protected readonly agentCommand = agentSessionCommand;

  protected readonly totals = computed(() => {
    const as = this.store.containerAgents();
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
    const c = this.store.containers().find(x => x.id === a.containerId);
    if (!c) return;
    this.store.openTerminal({
      hostId: c.hostId,
      containerId: c.id,
      label: a.name,
      agentKey: a.id,
      command: agentSessionCommand(a.name),
    });
  }

  protected upIntegrations(agentId: string): string[] {
    const a = this.store.agentById(agentId);
    return a ? a.integrations.filter(i => i.status === 'up' || i.status === 'degraded').map(i => i.kind) : [];
  }
}
