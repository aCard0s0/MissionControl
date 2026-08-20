import { Injectable, signal } from '@angular/core';

/**
 * A request to open the bottom terminal panel. Everything past `seq` is
 * optional: an empty request just opens the panel, while a targeted one (from
 * the agent shortcut) pins the tab to a container and runs a command in it.
 */
export interface TerminalRequest {
  /** monotonic — the panel acts once per new value, even for an identical target */
  seq: number;
  hostId?: string;
  containerId?: string;
  /** tab label; the profile name for an agent shortcut */
  label?: string;
  /** AgentProfile.id — lets a repeat click focus the tab it already opened */
  agentKey?: string;
  /** typed into the shell once it is live, and run */
  command?: string;
  /** typed into the shell once it is live but NOT run — the operator presses Enter. What the
   *  hermes command reference sends, so a click can never be the decision to run something. */
  insert?: string;
}

/** The channel pages use to summon the terminal panel. */
@Injectable({ providedIn: 'root' })
export class TerminalRequestStore {
  /** Set by pages that want the bottom terminal panel opened. Null until the
   *  first request; `seq` is what makes a repeat request with an identical
   *  target still register as a new one. */
  readonly request = signal<TerminalRequest | null>(null);

  private seq = 0;

  /**
   * Open the bottom terminal panel. With no target it behaves as it always
   * has — the panel seeds a tab on the globally selected container. With one,
   * the panel opens (or focuses) a tab bound to that container and types
   * `command` into it once the shell is live. `insert` is typed the same way but
   * left unrun, and works with or without a target.
   */
  open(target?: Omit<TerminalRequest, 'seq'>): void {
    this.request.set({ ...target, seq: ++this.seq });
  }
}
