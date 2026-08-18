import { signal } from '@angular/core';
import { McRuntimeConfig } from '../app-config';
import { HermesApi } from '../hermes-api';
import { MockHttp } from '../api/mock-http';

let uid = 0;

/** Mints an id for data that only exists in mock mode. */
export const nid = (prefix: string): string => `${prefix}-${Date.now().toString(36)}-${uid++}`;

/** Whatever a rejected promise carried, as something safe to show an operator. */
const errorMessage = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

export type BackendStatus = 'mock' | 'connecting' | 'connected' | 'unreachable';

/**
 * What every slice of the store shares: the runtime config, the data mode, the
 * backend client, and the toast channel. One instance is built by
 * {@link HermesStore} and handed to each slice, so `mock` and `api` stay a
 * single swappable seam rather than a copy per domain.
 */
export class StoreContext {
  /** True in `mock` data mode — read per call, never captured, so a test can
   *  flip the whole store onto a stubbed backend after construction. */
  mock: boolean;
  api: HermesApi;

  /** Health of the Mission Control backend API (live mode only). */
  readonly backendStatus = signal<BackendStatus>('mock');

  /** Transient error toast for failed live actions. */
  readonly liveError = signal<string | null>(null);

  constructor(readonly config: McRuntimeConfig) {
    this.mock = config.dataMode === 'mock';
    // mock mode answers through a fake HTTP layer, so a converted slice makes the
    // same call in both modes; the slices still carrying a mock branch never
    // reach it. See MockHttp for which domains have moved.
    this.api = new HermesApi(
      config.apiBaseUrl,
      this.mock ? new MockHttp(config.dockerSocket) : undefined);
    this.backendStatus.set(this.mock ? 'mock' : 'connecting');
  }

  toast(message: string): void {
    this.liveError.set(message);
    setTimeout(() => this.liveError.set(null), 6_000);
  }

  /** Toasts `<label> failed: <reason>` — the shape every live action reports. */
  toastFailure(label: string, error: unknown): void {
    this.toast(`${label} failed: ${errorMessage(error)}`);
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
