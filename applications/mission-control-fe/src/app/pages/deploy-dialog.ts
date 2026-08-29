import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivityStore } from '../core/store/activity-store';
import { AgentStore } from '../core/store/agent-store';
import { AgentRef } from '../core/api/agent-ref';
import { DeployedPart } from '../core/models';
import { Scrim } from '../shared/scrim';

/**
 * Puts something from a library onto one agent.
 *
 * Picks an agent rather than a container: a skill lives in a profile's own skills
 * directory, and a container holds several profiles, so "which container" is only half an
 * address. {@link AgentStore.resolve} turns the chosen agent back into the host, container
 * and profile the API is keyed by. Nothing here creates an agent — unlike the blueprint
 * deploy dialog, the target already exists and is layered onto.
 *
 * What is being deployed differs, so the caller passes {@link run} and projects its own
 * explanation as content. What happens next does not, which is why this is one component:
 * the agent picker, the busy state, the survives-being-closed rule and the report are the
 * same whether one skill or a whole guide is going over.
 *
 * {@link run} answers the parts worth reporting. An empty array is a plain success — a
 * single skill has nothing to enumerate — and null is a failure the store has already
 * toasted. A guide answers one row per part, because it can half-land, and the dialog then
 * stays open on that report: closing on a green tick would hide the case worth seeing.
 */
@Component({
  selector: 'mc-deploy-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Scrim],
  templateUrl: './deploy-dialog.html',
  styleUrl: './deploy-dialog.scss',
})
export class DeployDialog {
  /** Shown in the header — the name of the thing going over. */
  readonly label = input.required<string>();
  /** The verb on the button and in the header, e.g. `skill` or `guide`. */
  readonly noun = input.required<string>();
  /**
   * Runs the deploy. Must be a stable reference — bind a field, not an inline arrow, or
   * every change detection pass writes a new identity into this input.
   */
  readonly run = input.required<(agent: AgentRef) => Promise<DeployedPart[] | null>>();

  readonly closed = output<void>();

  protected readonly agents = inject(AgentStore);
  private readonly activity = inject(ActivityStore);

  protected readonly busy = signal(false);
  /** The report, or null before a deploy has answered. Empty means it worked. */
  protected readonly parts = signal<DeployedPart[] | null>(null);
  /** Set when the deploy failed outright, as opposed to a part of it. */
  protected readonly failed = signal(false);

  protected readonly targets = computed(() => this.agents.agents());

  protected readonly landed = computed(() =>
    (this.parts() ?? []).filter(p => p.status === 'deployed').length);
  protected readonly trouble = computed(() =>
    (this.parts() ?? []).filter(p => p.status !== 'deployed').length);

  /** True once this dialog is closed — the deploy it started runs on without it. */
  private gone = false;

  protected agentId = '';

  constructor() {
    inject(DestroyRef).onDestroy(() => { this.gone = true; });
  }

  protected async deploy(): Promise<void> {
    if (!this.agentId || this.busy()) return;
    const resolved = this.agents.resolve(this.agentId);
    if (!resolved) return;

    this.busy.set(true);
    this.failed.set(false);
    this.parts.set(null);
    const report = await this.activity.run(
      `deploying ${this.label()} to ${resolved.agent.name}`,
      () => this.run()(resolved.ref));

    // Closed while the deploy was in flight: it finished on its own, the store has already
    // said how it went, and this component is not on screen to show anyone anything.
    if (this.gone) return;

    this.busy.set(false);
    if (!report) {
      this.failed.set(true);
    } else if (report.length) {
      this.parts.set(report);
    } else {
      this.closed.emit();   // nothing to enumerate; the work is done
    }
  }
}
