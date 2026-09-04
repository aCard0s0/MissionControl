import {
  ChangeDetectionStrategy, Component, Injectable, effect, inject, signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Scrim } from './scrim';

/**
 * What a destructive action asks before it runs.
 *
 * `typed` is for the irreversible kind — a container, a profile, an MCP server — where the
 * operator retypes the name. Dashboard-owned records (a prompt, a credential, a blueprint)
 * use the same dialog without it: the friction has to match what is lost.
 */
export interface ConfirmRequest {
  /** The heading: what is about to happen, e.g. `delete prompt`. */
  title: string;
  /** What goes, and what stays. */
  message: string;
  /** The confirming button's label. Defaults to `delete`. */
  action?: string;
  /** When set, the action stays disabled until this exact text is typed. */
  typed?: string;
  /** A warning the operator may proceed past, rather than a deletion — plain heading, primary button. */
  warn?: boolean;
}

/**
 * The one confirmation dialog, asked from anywhere.
 *
 * <p>Ten pages used the browser's `confirm()` while the container and profile deletes had
 * proper dialogs, so the same act — deleting something — looked like two different apps.
 * This is the dialog every one of them now goes through; the page keeps only the question.
 *
 * <p>A service rather than a component per page because a dialog is not part of any page's
 * state: `ask()` returns the answer, and {@link ConfirmDialog} in the shell renders whatever
 * is pending. Asking while one is open answers the first with `false` — two questions at once
 * is a bug upstream, and the safe reading of it is "no".
 */
@Injectable({ providedIn: 'root' })
export class Confirm {
  readonly pending = signal<(ConfirmRequest & { settle: (ok: boolean) => void }) | null>(null);

  /** Resolves `true` on the action button; `false` on cancel, Escape or a click outside. */
  ask(request: ConfirmRequest): Promise<boolean> {
    this.pending()?.settle(false);
    return new Promise(resolve => this.pending.set({ ...request, settle: resolve }));
  }

  answer(ok: boolean): void {
    const asked = this.pending();
    if (!asked) return;
    this.pending.set(null);
    asked.settle(ok);
  }
}

/** Renders the pending {@link Confirm} question. One instance, in the app shell. */
@Component({
  selector: 'mc-confirm',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, Scrim],
  template: `
    @if (confirm.pending(); as ask) {
      <div class="scrim" mcScrim (dismiss)="confirm.answer(false)">
        <div class="modal" role="dialog" aria-modal="true" aria-labelledby="mc-confirm-title">
          <div class="panel-h" [class.crit-h]="!ask.warn" id="mc-confirm-title">{{ ask.title }}</div>
          <div class="modal-b">
            <p class="dim">{{ ask.message }}</p>
            @if (ask.typed) {
              <div class="field">
                <label for="mc-confirm-typed">type <span class="mono">{{ ask.typed }}</span> to confirm</label>
                <input id="mc-confirm-typed" class="input" [ngModel]="typed()"
                       (ngModelChange)="typed.set($event)" [placeholder]="ask.typed" />
              </div>
            }
            <div class="modal-actions">
              <button class="btn ghost" (click)="confirm.answer(false)">cancel</button>
              <button class="btn" [class.danger]="!ask.warn" [class.primary]="ask.warn"
                      [disabled]="!!ask.typed && typed() !== ask.typed"
                      (click)="confirm.answer(true)">{{ ask.action ?? 'delete' }}</button>
            </div>
          </div>
        </div>
      </div>
    }
  `,
})
export class ConfirmDialog {
  protected readonly confirm = inject(Confirm);
  protected readonly typed = signal('');

  constructor() {
    // a fresh question starts with an empty box, whatever the last one had typed into it
    effect(() => { this.confirm.pending(); this.typed.set(''); });
  }
}
