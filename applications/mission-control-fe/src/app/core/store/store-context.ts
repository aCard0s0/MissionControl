import { Injectable, inject, signal } from '@angular/core';
import { MC_CONFIG, McRuntimeConfig } from '../app-config';
import { errorMessage } from '../errors';
import { HermesApi } from '../hermes-api';

export type BackendStatus = 'connecting' | 'connected' | 'unreachable';

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

  /** Transient error toast for a failed action. */
  readonly liveError = signal<string | null>(null);

  readonly config: McRuntimeConfig = inject(MC_CONFIG);

  constructor() {
    this.api = new HermesApi(this.config.apiBaseUrl);
  }

  toast(message: string): void {
    this.liveError.set(message);
    setTimeout(() => this.liveError.set(null), 6_000);
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
