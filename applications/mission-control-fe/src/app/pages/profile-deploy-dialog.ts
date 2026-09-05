import {
  ChangeDetectionStrategy, Component, computed, DestroyRef, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { Confirm } from '../shared/confirm';
import { FormsModule } from '@angular/forms';
import { ActivityStore } from '../core/store/activity-store';
import { ContainerStore } from '../core/store/container-store';
import { ProviderStore } from '../core/store/provider-store';
import { TemplateStore } from '../core/store/template-store';
import { ProfileTemplate } from '../core/models';
import { templateProvidesKey } from '../shared/provider-resolve';
import { Scrim } from '../shared/scrim';

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
  imports: [FormsModule, Scrim],
  templateUrl: './profile-deploy-dialog.html',
  styleUrl: './profile-deploy-dialog.scss',
})
export class ProfileDeployDialog {
  readonly template = input.required<ProfileTemplate>();

  /** The id of the agent the deploy created. */
  readonly deployed = output<string>();
  readonly closed = output<void>();

  protected readonly containers = inject(ContainerStore);
  private readonly confirm = inject(Confirm);
  protected readonly providers = inject(ProviderStore);
  protected readonly templates = inject(TemplateStore);
  private readonly activity = inject(ActivityStore);
  protected readonly busy = signal(false);

  /** Set when a deploy came back empty, so the form stays and says why it is still here. */
  protected readonly failed = signal(false);

  /** True once this dialog is closed — the deploy it started runs on without it. */
  private gone = false;

  protected containerId = '';
  protected name = '';

  /** A profile is created by exec-ing into the container, so a stopped one is not a target. */
  protected readonly targets = computed(() =>
    this.containers.containers().filter(c => c.status !== 'stopped'));

  constructor() {
    inject(DestroyRef).onDestroy(() => { this.gone = true; });
    // the template arrives with the input, which is bound after construction
    effect(() => {
      const template = this.template();
      untracked(() => {
        // hermes folds a profile name to lower case on create, so the field shows the name
        // the agent will actually get rather than one the deploy would silently change
        this.name = template.name.toLowerCase();
        const targets = this.targets();
        const selected = targets.find(c => c.id === this.containers.selectedContainerId());
        this.containerId = (selected ?? targets[0])?.id ?? '';
      });
    });
  }

  /** True while the typed name is not what hermes would create. */
  protected folded(): boolean {
    return this.name !== this.name.toLowerCase();
  }

  protected async deploy(): Promise<void> {
    const template = this.template();
    const name = this.name.trim().toLowerCase();
    if (!this.containerId || !name || this.busy()) return;
    // only await when there is something to ask: a blueprint with its key in place must go
    // busy on the click itself, or a second click lands before the first is marked in flight
    const missing = this.missingKey(template);
    if (missing && !await this.confirm.ask({
      title: `no ${missing.envVar}`,
      message: `This template has no usable ${missing.envVar} for ${missing.label}. The deployed agent `
        + `may fail to authenticate until you add the key on its Setup tab.`,
      action: 'deploy anyway',
      warn: true,
    })) return;

    this.busy.set(true);
    this.failed.set(false);
    const id = await this.activity.run(`deploying ${name}`,
      () => this.templates.deploy(template.id, this.containerId, name));

    // Closed while the deploy was in flight: it finished on its own, the store has already
    // said how it went, and this component is not on screen to route anyone anywhere.
    if (this.gone) return;

    this.busy.set(false);
    if (id) this.deployed.emit(id);
    else this.failed.set(true);
  }

  /** The provider whose key this blueprint needs and does not carry, or null when it may go. */
  private missingKey(template: ProfileTemplate): { envVar: string; label: string } | null {
    const info = this.providers.llmProviders().find(p => p.key === template.provider);
    if (!info?.needsKey || !info.envVar || templateProvidesKey(template, info)) return null;
    return { envVar: info.envVar, label: info.label };
  }
}
