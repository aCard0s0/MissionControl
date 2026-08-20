import {
  ChangeDetectionStrategy, Component, computed, effect, inject, signal, untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { StoreContext } from '../core/store/store-context';
import { errorMessage } from '../core/errors';
import { ago, uptime } from '../core/format';
import { ApiServerInfo } from '../core/hermes-api';
import { LogEntry } from '../core/models';
import { LogView } from '../shared/log-view';
import { Reveal } from '../shared/reveal';

/** Matches the container tail's cadence — the two panels sit beside each other. */
const POLL_INTERVAL = 5_000;
const TAIL = 400;

/**
 * Mission Control's own log tail.
 *
 * <p>The dashboard shows a tail for every container it manages and, until this page, none for
 * itself — so the one process whose logs explain a failed deploy, a rolled-back upgrade or a
 * stuck MCP operation was the one an operator had to leave the dashboard to read.
 *
 * <p>Owns its poll rather than adding a store: nothing outside this page reads the server's
 * own log, and a root-provided store would keep polling while the page is closed.
 */
@Component({
  selector: 'mc-server-logs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, LogView, Reveal],
  templateUrl: './server-logs.html',
  styleUrl: './server-logs.scss',
})
export class ServerLogsPage {
  private readonly ctx = inject(StoreContext);

  protected readonly ago = ago;

  protected readonly lines = signal<LogEntry[]>([]);
  protected readonly info = signal<ApiServerInfo | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly updatedAt = signal<number | null>(null);
  protected readonly paused = signal(false);

  protected readonly errorCount = computed(() =>
    this.lines().filter(l => l.level === 'error').length);

  protected readonly warnCount = computed(() =>
    this.lines().filter(l => l.level === 'warn').length);

  protected readonly runningFor = computed(() => {
    const started = this.info()?.startedAt;
    return started ? uptime(started) : '—';
  });

  constructor() {
    void this.loadInfo();
    effect(onCleanup => {
      // reading the signal is what re-arms the timer when the operator resumes
      const paused = this.paused();
      untracked(() => void this.load());
      if (paused) return;
      const timer = setInterval(() => void this.load(), POLL_INTERVAL);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected refresh(): void {
    void this.load();
  }

  /** Pausing holds the tail still while an operator reads it — a 5s repaint loses their place. */
  protected togglePause(): void {
    this.paused.update(p => !p);
  }

  private async load(): Promise<void> {
    if (this.loading()) return;
    this.loading.set(true);
    try {
      const lines = await this.ctx.api.server.logs(TAIL);
      // the tail arrives newest-first, which is the order the panel renders
      this.lines.set(lines.map(l => ({ ...l, agentId: null })));
      this.updatedAt.set(Date.now());
      this.error.set(null);
    } catch (e) {
      this.error.set(errorMessage(e, 'server log read failed'));
    } finally {
      this.loading.set(false);
    }
  }

  private async loadInfo(): Promise<void> {
    try {
      this.info.set(await this.ctx.api.server.info());
    } catch {
      // the header degrades to '—'; the tail below is the point of the page
    }
  }
}
