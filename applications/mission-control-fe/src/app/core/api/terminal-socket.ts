import { normalizeBase, seg } from './http';

/**
 * `/ws/terminal` — the one backend surface that is not HTTP.
 *
 * <p>Lives beside the REST clients rather than in the terminal component because it is the
 * same kind of thing: an endpoint this app talks to, addressed off the same configured base.
 * It was built inline in {@link TerminalSession}, which meant the app decided how to turn
 * `apiBaseUrl` into a URL in two places, with two different answers — {@link ApiHttp} treating
 * an empty base as same-origin-relative, and the terminal needing an absolute one because a
 * WebSocket has no relative form.
 */

/** The whole of the structured protocol. Binary frames in both directions carry raw PTY
 *  bytes and have no shape to declare. */
export const resizeFrame = (cols: number, rows: number): string =>
  JSON.stringify({ type: 'resize', cols, rows });

/**
 * The socket URL for a shell in `containerId` on `hostId`.
 *
 * <p>Resolved through {@link URL} against the page rather than by rewriting the string. The
 * base is operator-supplied through `config.js`, so it arrives in every shape a person writes
 * one in: empty for same-origin, absolute, scheme-relative (`//host/api`), or a path. A
 * `.replace(/^http/, 'ws')` answers correctly for the absolute http(s) case and silently
 * builds an unusable URL for the rest — a schemeless string stays schemeless — and the failure
 * surfaces only as a shell that never connects.
 *
 * <p>Resolution never fails: a base the URL parser cannot make sense of on its own is read
 * relative to the page, the same way a browser reads a schemeless href. That is a wrong
 * address for a badly configured deployment, but a well-formed one — which is what makes it
 * visible in a network panel instead of dying inside the WebSocket constructor.
 */
export function terminalSocketUrl(apiBaseUrl: string, hostId: string, containerId: string): string {
  const base = normalizeBase(apiBaseUrl);
  const here = typeof location !== 'undefined' ? location.href : 'http://localhost/';
  const url = new URL(`${base}/ws/terminal`, here);
  // ws over http — anything else (https, and file:/blob: which have no ws form to pick at all)
  // gets the secure one, which is the safe guess
  url.protocol = url.protocol === 'http:' ? 'ws:' : 'wss:';
  url.search = `?hostId=${seg(hostId)}&containerId=${seg(containerId)}`;
  return url.toString();
}
