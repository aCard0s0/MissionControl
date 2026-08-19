import {
  ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked,
} from '@angular/core';
import { McpCatalogStore } from '../core/store/mcp-catalog-store';
import { errorMessage } from '../core/errors';
import { clock } from '../core/format';
import { LogEntry, McpCatalogServer } from '../core/models';

/** A managed server's log tail is short-lived, so it is re-read rather than streamed. */
const POLL_INTERVAL = 3_000;
const TAIL = 150;

/**
 * Modal log tail for one managed MCP server. Owns its own poll: the effect below
 * restarts it when a different server is shown and tears it down when the modal
 * is destroyed, so a closed viewer never leaves an interval running.
 */
@Component({
  selector: 'mc-mcp-server-logs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './mcp-server-logs.html',
  styleUrl: './mcp-server-logs.scss',
})
export class McpServerLogs {
  readonly server = input.required<McpCatalogServer>();
  readonly closed = output<void>();

  private readonly catalog = inject(McpCatalogStore);
  protected readonly clock = clock;

  protected readonly lines = signal<LogEntry[]>([]);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(onCleanup => {
      const id = this.server().id;
      untracked(() => {
        this.lines.set([]);
        this.error.set(null);
        // a read for the previous server may still be in flight; it will be
        // dropped on arrival, so it must not hold this one back
        this.loading.set(false);
        void this.load(id);
      });
      const timer = setInterval(() => void this.load(id), POLL_INTERVAL);
      onCleanup(() => clearInterval(timer));
    });
  }

  /** One read, dropped on arrival if the modal has since moved to another server. */
  private async load(id: string): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    try {
      const lines = await this.catalog.logTail(id, TAIL);
      if (this.server().id !== id) return;
      this.lines.set(lines);
      this.error.set(null);
    } catch (error) {
      if (this.server().id !== id) return;
      this.error.set(errorMessage(error, 'log read failed'));
    } finally {
      this.loading.set(false);
    }
  }
}
