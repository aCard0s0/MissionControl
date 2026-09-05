import { ChangeDetectionStrategy, Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HOST_ACCESS_PRESETS, HostAccessPreset, applyPreset } from '../core/host-access';
import { EnvVar, HostAccess, Mount, PortMapping } from '../core/models';

/**
 * The ports, variables and mounts a form opens between a container and its host.
 *
 * The same rows sit on the deploy form and on the update dialog, because those are the two
 * moments Docker lets any of them be set: at create, and at the recreate an update already is.
 * Rows are edited in place on the object the parent owns and reads back on submit; a preset
 * replaces the object, which is what the two-way `access` is for. The projected content is the
 * hint shown while nothing is asked — the two forms say different things there.
 */
@Component({
  selector: 'mc-host-access-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './host-access-editor.html',
  styleUrl: './host-access-editor.scss',
})
export class HostAccessEditor {
  readonly access = model.required<HostAccess>();
  /** The caption over the group. */
  readonly caption = input.required<string>();

  protected readonly presets = HOST_ACCESS_PRESETS;

  protected preset(id: HostAccessPreset): void {
    this.access.set(applyPreset(this.access(), id));
  }

  protected addPort(): void {
    this.access().ports.push({ containerPort: 0, hostPort: 0, hostIp: '127.0.0.1' });
  }

  protected addVariable(): void {
    this.access().env.push({ key: '', value: '' });
  }

  protected addMount(): void {
    this.access().mounts.push({ source: '', target: '', readOnly: false });
  }

  protected removeRow(rows: PortMapping[] | EnvVar[] | Mount[], index: number): void {
    rows.splice(index, 1);
  }

  protected open(): boolean {
    const a = this.access();
    return a.ports.length + a.env.length + a.mounts.length > 0;
  }
}
