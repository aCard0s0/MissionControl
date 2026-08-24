import { ApiError } from '../errors';

/**
 * The one place that talks to `fetch`. Every resource client under this folder
 * composes a path and hands it to {@link ApiHttp.req} — so timeouts, the JSON
 * content type, error-body unwrapping and the empty-body case are decided once.
 */
export class ApiHttp {
  private readonly base: string;

  constructor(apiBaseUrl: string) {
    this.base = normalizeBase(apiBaseUrl);
  }

  async req<T>(path: string, init?: RequestInit, timeoutMs = 15_000): Promise<T> {
    const res = await fetch(this.base + path, {
      headers: { 'Content-Type': 'application/json' },
      // abort a hung request so the pollers can't pile up pending fetches across
      // ticks when the backend is slow; callers override with their own signal
      signal: AbortSignal.timeout(timeoutMs),
      ...init,
    });
    if (!res.ok) {
      let detail = `${res.status}`;
      try {
        const body = await res.json();
        if (body?.error) detail = body.error;
      } catch { /* non-json error body */ }
      // carries the status too: a caller that only shows the text reads
      // `message` as before, one that has to tell 404 from 500 no longer has to
      // parse it back out of a string
      throw new ApiError(res.status, detail);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  /** GET shorthand — the default for every read endpoint. */
  get<T>(path: string, timeoutMs?: number): Promise<T> {
    return this.req<T>(path, undefined, timeoutMs);
  }

  post<T>(path: string, body?: unknown, timeoutMs?: number): Promise<T> {
    return this.req<T>(path, { method: 'POST', ...bodyInit(body) }, timeoutMs);
  }

  put<T>(path: string, body?: unknown): Promise<T> {
    return this.req<T>(path, { method: 'PUT', ...bodyInit(body) });
  }

  patch<T>(path: string, body?: unknown): Promise<T> {
    return this.req<T>(path, { method: 'PATCH', ...bodyInit(body) });
  }

  delete<T>(path: string): Promise<T> {
    return this.req<T>(path, { method: 'DELETE' });
  }
}

function bodyInit(body: unknown): RequestInit {
  return body === undefined ? {} : { body: JSON.stringify(body) };
}

/** Drops trailing slashes, so a configured base written with one and one written without
 *  address the same endpoint. Shared with {@link terminalSocketUrl}: the WebSocket endpoint
 *  hangs off the same base, and the two must not disagree about what it is. */
export const normalizeBase = (apiBaseUrl: string): string => apiBaseUrl.replace(/\/+$/, '');

/** Percent-encodes one path segment. Ids and profile names are operator input,
 *  so an unescaped `/` or `..` would address a different endpoint. */
export const seg = (value: string | number): string => encodeURIComponent(String(value));
