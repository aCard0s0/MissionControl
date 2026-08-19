import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HostStore } from '../core/store/host-store';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { McpHealthcheck, McpNamedVolume } from '../core/models';
import {
  McpEditorDraft, McpEditorEntry, applyMcpKindDefaults, blankConfigEntry, blankSupportService,
  blankVolume, defaultHealthcheck, mcpDraftToInput, mcpDraftValid, splitMcpLines,
} from './mcp-editor';

/**
 * The modal form behind one catalog entry. The page decides which draft to open
 * — new, edit, or duplicate — and this owns everything after that: the repeating
 * sub-forms, the save, and the busy flag that keeps the scrim from dismissing a
 * save still in flight.
 *
 * The rules live in ./mcp-editor as pure functions, so all that is left here is
 * the form's own bookkeeping. Every repeating list is edited through the same
 * three row helpers, whether it belongs to the server or to one of its support
 * services.
 */
@Component({
  selector: 'mc-mcp-server-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './mcp-server-editor.html',
  styleUrl: './mcp-server-editor.scss',
})
export class McpServerEditor {
  /** The draft on screen. The form binds straight into it, so it is mutated in
   *  place rather than replaced — the page hands over the same object it built. */
  readonly draft = input.required<McpEditorDraft>();

  /** The id the backend answered with, once a save lands. */
  readonly saved = output<string>();
  readonly closed = output<void>();

  private readonly catalog = inject(McpCatalogStore);
  protected readonly hosts = inject(HostStore);
  protected readonly splitLines = splitMcpLines;
  protected readonly saveBusy = signal(false);

  protected kindChanged(): void {
    applyMcpKindDefaults(this.draft());
  }

  protected addEntry(rows: McpEditorEntry[]): void {
    rows.push(blankConfigEntry());
  }

  protected addVolume(rows: McpNamedVolume[]): void {
    rows.push(blankVolume());
  }

  protected addSupportService(): void {
    this.draft().supportServices.push(blankSupportService());
  }

  protected removeRow(rows: unknown[], index: number): void {
    rows.splice(index, 1);
  }

  /** Both the server and each support service can carry one. */
  protected toggleHealthcheck(target: { healthcheck?: McpHealthcheck | null }): void {
    target.healthcheck = target.healthcheck ? null : defaultHealthcheck();
  }

  protected draftValid(): boolean {
    return mcpDraftValid(this.draft(), this.catalog.servers());
  }

  protected async save(): Promise<void> {
    const draft = this.draft();
    if (!this.draftValid() || this.saveBusy()) return;
    this.saveBusy.set(true);
    const id = await this.catalog.save(mcpDraftToInput(draft), draft.id ?? undefined);
    this.saveBusy.set(false);
    if (id) this.saved.emit(id);
  }
}
