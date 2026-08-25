import { Injectable, computed, inject, signal } from '@angular/core';
import { MC_CONFIG, McRuntimeConfig } from '../app-config';
import { errorMessage } from '../errors';
import { HermesApi } from '../hermes-api';

export type BackendStatus = 'connecting' | 'connected' | 'unreachable';

/** A confirmation or a failure, as the notification stack shows it. */
export type ToastKind = 'ok' | 'error';

export interface Toast {
  readonly id: number;
  readonly kind: ToastKind;
  readonly message: string;
  /** When it was raised — the stack orders by this, so a toast keeps its slot. */
  readonly at: number;
}

/** How long a toast stays before it withdraws itself. */
const TOAST_MS = 6_000;

/**
 * What every slice of the store shares: the runtime config, the backend client
 * and the toast channel. Root-provided, so every slice injecting it gets the
 * same one and `api` stays a single swappable seam — which is also how a test
 * substitutes the backend for the whole store at once.
 */
@Injectable({ providedIn: 'root' })
export class StoreContext {
  api: HermesApi;

  /** Health of the Mission Control backend API. */
  readonly backendStatus = signal<BackendStatus>('connecting');

  /**
   * Everything currently on the notification stack, oldest first.
   *
   * <p>A queue rather than one signal per severity. Actions overlap — a deploy
   * confirms while an earlier failure is still being read, two probes fail
   * together — and a single slot per kind meant the newer message silently took
   * the older one's place, and inherited what was left of its timer.
   */
  readonly toasts = signal<readonly Toast[]>([]);

  /** The newest failure still on screen, for callers that want only the words. */
  readonly liveError = computed(() => this.newest('error'));

  /** The newest confirmation still on screen. */
  readonly liveNotice = computed(() => this.newest('ok'));

  readonly config: McRuntimeConfig = inject(MC_CONFIG);

  private nextToastId = 1;

  constructor() {
    this.api = new HermesApi(this.config.apiBaseUrl);
  }

  toast(message: string): void {
    this.push('error', message);
  }

  /**
   * Confirms an action that worked.
   *
   * <p>Failure has always spoken here; success used to say nothing, which is
   * only readable when the result is on screen already. It is not for a deploy
   * the operator started and then navigated away from — so the actions that run
   * long enough to leave their own page confirm through this.
   */
  notify(message: string): void {
    this.push('ok', message);
  }

  /** Takes one toast off the stack — its own timer, or the operator dismissing it. */
  dismiss(id: number): void {
    this.toasts.update(list => list.filter(t => t.id !== id));
  }

  /** Each toast carries its own timer, so a later one never shortens an earlier one. */
  private push(kind: ToastKind, message: string): void {
    const id = this.nextToastId++;
    this.toasts.update(list => [...list, { id, kind, message, at: Date.now() }]);
    setTimeout(() => this.dismiss(id), TOAST_MS);
  }

  private newest(kind: ToastKind): string | null {
    const matching = this.toasts().filter(t => t.kind === kind);
    return matching.length ? matching[matching.length - 1].message : null;
  }

  /**
   * Toasts `<label> failed: <reason>` — the shape every live action reports.
   *
   * Which failures speak at all is a question about who asked, and the answer is
   * the same everywhere in this store:
   *  - an operator action always says why it failed, through this or through
   *    {@link gone}. A control that answers nothing is indistinguishable from a
   *    broken one, so no action path returns quietly;
   *  - a background refresh stays quiet and keeps its last known state. The next
   *    poll is the retry, and a toast per tick would bury the one an action
   *    raised.
   */
  toastFailure(label: string, error: unknown): void {
    this.toast(`${label} failed: ${errorMessage(error)}`);
  }

  /**
   * Reports an action aimed at something that is no longer there — a profile
   * another operator removed, a container recreated under a new id between the
   * render and the click — and answers `false`, so a guard reads as
   * `if (!resolved) return this.ctx.gone('profile');`.
   */
  gone(subject: string): false {
    this.toast(`${subject} is no longer available`);
    return false;
  }

  /** Run `fn` over `items` with at most `limit` in flight at once. Caps the
   *  per-container fan-out of the pollers so a slow daemon can't open dozens of
   *  concurrent requests every tick. */
  async mapPool<T, R>(items: readonly T[], limit: number, fn: (item: T) => Promise<R>): Promise<R[]> {
    const results: R[] = new Array(items.length);
    let next = 0;
    const worker = async () => {
      while (next < items.length) {
        const idx = next++;
        results[idx] = await fn(items[idx]);
      }
    };
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
    return results;
  }
}
