import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AgentStore } from '../core/store/agent-store';
import { ContainerStore } from '../core/store/container-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { HermesCommands } from '../shared/hermes-commands';
import { Reveal } from '../shared/reveal';

/**
 * The hermes CLI, browsable.
 *
 * The dashboard drives a handful of these commands and reads the files the rest of them
 * write; everything it has no button for is done at a prompt inside the container. This page
 * is that surface area laid out to read, with the same list the terminal panel's drawer uses
 * — the drawer is for when you are already at the prompt, this is for when you are working
 * out what to type.
 *
 * Scoping to a profile is the part a static reference cannot do: pick an agent and every line
 * carries its `-p`, which is the difference between a command that reads the right profile
 * and one that quietly reads `default`.
 */
@Component({
  selector: 'mc-reference',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [HermesCommands, Reveal],
  templateUrl: './reference.html',
  styleUrl: './reference.scss',
})
export class ReferencePage {
  protected readonly agents = inject(AgentStore);
  protected readonly containers = inject(ContainerStore);
  private readonly terminal = inject(TerminalRequestStore);

  /** AgentProfile.id whose `-p` scopes every line, or null for a bare invocation. */
  protected readonly scopeId = signal<string | null>(null);

  protected readonly scoped = computed(() => {
    const id = this.scopeId();
    if (!id) return undefined;
    return this.agents.forSelectedContainer().find(a => a.id === id)?.name;
  });

  /**
   * Send the line to the bottom terminal panel, which types it at the prompt and stops there.
   * Targeted at the agent's own container when one is scoped, so the shell is in the right
   * place; otherwise the panel falls back to the selected container as it does for any
   * untargeted request.
   */
  protected send(line: string): void {
    const agent = this.agents.forSelectedContainer().find(a => a.id === this.scopeId());
    const container = this.containers.containers().find(c => c.id === agent?.containerId)
      ?? this.containers.selected();
    this.terminal.open(container
      ? { hostId: container.hostId, containerId: container.id, label: container.name, insert: line }
      : { insert: line });
  }
}
