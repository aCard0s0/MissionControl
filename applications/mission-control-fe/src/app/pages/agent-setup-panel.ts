import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { AgentProfile } from '../core/models';
import { StatusDot } from '../shared/status-dot';

/**
 * The profile's Setup tab: what its `.env` holds, which auth providers are
 * logged in, and which messaging platforms are wired up.
 *
 * The setup itself is cached in the store, because reading it runs
 * `hermes status` inside the container and takes seconds — so re-entering the
 * tab renders the last answer immediately, and only an explicit refresh (or a
 * write, which answers with the new state) goes back to the container.
 */
@Component({
  selector: 'mc-agent-setup-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot],
  templateUrl: './agent-setup-panel.html',
  styleUrl: './agent-setup-panel.scss',
})
export class AgentSetupPanel {
  readonly agent = input.required<AgentProfile>();

  protected readonly setup = inject(AgentSetupStore);
  protected readonly terminal = inject(TerminalRequestStore);

  protected readonly profileSetup = computed(() => this.setup.setupOf(this.agent().id));
  protected readonly setupLoading = computed(() => this.setup.isSetupLoading(this.agent().id));

  /** env var (or '.env' for the init call) with a write in flight. */
  protected readonly envBusy = signal<string | null>(null);
  /** tokenVar of the expanded messaging row. */
  protected readonly msgOpen = signal<string | null>(null);
  protected envDrafts: Record<string, string> = {};

  constructor() {
    // read on open, and whenever the tab is showing a different profile; a
    // cached setup answers without touching the container
    effect(() => {
      const id = this.agent().id;
      untracked(() => {
        this.msgOpen.set(null);
        this.envDrafts = {};
        void this.setup.setup(id);
      });
    });
  }

  protected refresh(): void {
    void this.setup.setup(this.agent().id, true);
  }

  protected initEnv(): void {
    this.envBusy.set('.env');
    this.setup.initEnv(this.agent().id)
      .catch(() => null)
      .finally(() => this.envBusy.set(null));
  }

  protected setEnv(key: string): void {
    const value = (this.envDrafts[key] ?? '').trim();
    if (!value) return;
    this.applyEnv(key, value);
  }

  protected clearEnv(key: string): void {
    this.applyEnv(key, null);
  }

  protected toggleMsg(tokenVar: string): void {
    this.msgOpen.update(v => v === tokenVar ? null : tokenVar);
  }

  /** An empty value removes the key; the store caches whatever comes back. */
  private applyEnv(key: string, value: string | null): void {
    this.envBusy.set(key);
    this.setup.setEnv(this.agent().id, [{ key, value }])
      .catch(() => null)
      .then(saved => { if (saved) delete this.envDrafts[key]; })
      .finally(() => this.envBusy.set(null));
  }
}
