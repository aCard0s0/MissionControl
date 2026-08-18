import {
  ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HermesStore } from '../core/hermes-store';
import { ProfileTemplate } from '../core/models';
import { templateProvidesKey } from '../shared/provider-resolve';

/**
 * Deploys one blueprint into a container as a new agent. The only judgement it
 * makes is the warning below: a template whose provider needs a key but carries
 * no usable one produces an agent that cannot authenticate until the operator
 * adds the key on its Setup tab, which is worth saying before the deploy rather
 * than after.
 */
@Component({
  selector: 'mc-profile-deploy-dialog',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './profile-deploy-dialog.html',
  styleUrl: './profile-deploy-dialog.scss',
})
export class ProfileDeployDialog {
  readonly template = input.required<ProfileTemplate>();

  /** The id of the agent the deploy created. */
  readonly deployed = output<string>();
  readonly closed = output<void>();

  protected readonly store = inject(HermesStore);
  protected readonly busy = signal(false);

  protected containerId = '';
  protected name = '';

  constructor() {
    // the template arrives with the input, which is bound after construction
    effect(() => {
      const template = this.template();
      untracked(() => {
        this.name = template.name;
        this.containerId =
          this.store.selectedContainerId() || this.store.containers()[0]?.id || '';
      });
    });
  }

  protected async deploy(): Promise<void> {
    const template = this.template();
    const name = this.name.trim();
    if (!this.containerId || !name || this.busy()) return;
    if (!this.confirmMissingKey(template)) return;

    this.busy.set(true);
    const id = await this.store.deployTemplate(template.id, this.containerId, name);
    this.busy.set(false);
    if (id) this.deployed.emit(id);
  }

  /** True to go ahead: either the key is there, or the operator accepted the risk. */
  private confirmMissingKey(template: ProfileTemplate): boolean {
    const info = this.store.llmProviders().find(p => p.key === template.provider);
    if (!info?.needsKey || !info.envVar || templateProvidesKey(template, info)) return true;
    return confirm(
      `This template has no usable ${info.envVar} for ${info.label}. The deployed agent `
      + `may fail to authenticate until you add the key on its Setup tab. Deploy anyway?`);
  }
}
