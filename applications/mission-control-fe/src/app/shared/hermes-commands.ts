import {
  ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal,
} from '@angular/core';
import {
  HERMES_COMMANDS, HERMES_DOCS, HermesCommand, REWRITES_INSTALL,
  hermesDocsUrl, hermesLine, searchHermesCommands,
} from '../core/hermes-commands';
import { copyText } from './copy-text';

/** How long a row stays marked as copied — long enough to read, short enough not to linger. */
const COPIED_MS = 1200;

/**
 * The hermes CLI reference, as a searchable list of runnable lines.
 *
 * One component behind two surfaces: the drawer inside the terminal panel, where the point is
 * to put a line at the prompt without leaving it, and the Reference page, where the point is
 * to read. The `dark` input is what tells them apart — the terminal chrome is pinned to the
 * `--term-*` tokens in both themes, so inside it this list has to be too.
 *
 * Nothing here runs anything. `insert` emits the line and the host decides what to do with
 * it; the terminal types it *without* a newline, so the operator is still the one who presses
 * Enter. That is deliberate for a list that contains `uninstall` and `config set`.
 */
@Component({
  selector: 'mc-hermes-commands',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './hermes-commands.html',
  styleUrl: './hermes-commands.scss',
  host: { '[class.dark]': 'dark()' },
})
export class HermesCommands {
  /** Scopes every line with `-p <profile>`; absent leaves them bare. */
  readonly profile = input<string | undefined>(undefined);
  /** Show the insert action — off where there is no shell to insert into. */
  readonly canInsert = input(false);
  /** Render against the terminal's own dark tokens instead of the page's. */
  readonly dark = input(false);

  /** The line to put at the prompt, already profile-scoped. */
  readonly insert = output<string>();

  protected readonly docs = HERMES_DOCS;
  protected readonly installWarning = REWRITES_INSTALL;
  protected readonly total = HERMES_COMMANDS.length;
  protected readonly docsUrl = hermesDocsUrl;

  protected readonly query = signal('');
  /** The command last copied, so one row can confirm without a toast. */
  protected readonly copied = signal<string | null>(null);

  protected readonly groups = computed(() => searchHermesCommands(this.query()));
  protected readonly matches = computed(() =>
    this.groups().reduce((n, g) => n + g.commands.length, 0));

  private copiedTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    // the drawer is torn down every time the panel closes; a pending timer would wake a
    // destroyed component and write to its signal
    inject(DestroyRef).onDestroy(() => {
      if (this.copiedTimer) clearTimeout(this.copiedTimer);
    });
  }

  protected line(command: HermesCommand): string {
    return hermesLine(command, this.profile());
  }

  protected onSearch(value: string): void {
    this.query.set(value);
  }

  protected async copy(command: HermesCommand): Promise<void> {
    const ok = await copyText(this.line(command));
    if (!ok) return;
    this.copied.set(command.cmd);
    if (this.copiedTimer) clearTimeout(this.copiedTimer);
    this.copiedTimer = setTimeout(() => this.copied.set(null), COPIED_MS);
  }

  protected onInsert(command: HermesCommand): void {
    this.insert.emit(this.line(command));
  }
}
