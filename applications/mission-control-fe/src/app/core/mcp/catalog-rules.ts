import { McpCatalogServer, McpHealthcheck, McpNamedVolume, McpOperationState } from '../models';

// What is true of a catalog entry no matter who is asking: the editor deciding
// whether a draft can be saved, the store refusing a name the catalog already
// answers to, the roster rendering an address, and both of them deciding whether
// an entry is mid-operation. Each rule used to live beside one of those callers,
// which is how the name check ended up written twice and how the editor's
// validation ended up in the pages package.

/**
 * The two states that mean no operation is in flight, out of the eight in
 * {@link McpOperationState}.
 *
 * `error` is settled on purpose: a failure the operator can act on is not a run still going.
 *
 * This used to carry five more names — '', 'none', 'failed', 'complete', 'completed' — that
 * nothing on the backend can produce. They read as tolerance and were dead branches.
 */
const SETTLED_OPERATIONS: readonly string[] = ['idle', 'error'] satisfies McpOperationState[];

/** A docker volume name, and an absolute mount target that cannot escape upward. */
const VOLUME_NAME = /^[a-z0-9][a-z0-9_.-]{0,62}$/;

/** Compose service names, which a managed entry mints one of per support service. */
const SUPPORT_SERVICE_NAME = /^[a-z0-9][a-z0-9-]{0,62}$/;

/** Header names and environment keys have different legal alphabets. */
const HEADER_NAME = /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/;
const ENV_KEY = /^[A-Za-z_][A-Za-z0-9_]*$/;

/** `interval`/`timeout`/`startPeriod` as docker spells a duration. */
const DURATION = /^[1-9][0-9]*(?:\.[0-9]+)?(?:ns|us|ms|s|m|h)$/;

/** The healthcheck verbs a Compose service accepts. */
const HEALTHCHECK_VERBS = ['CMD', 'CMD-SHELL', 'NONE'];

/**
 * True while a managed MCP server is mid-operation (pulling, starting,
 * applying, …) and the UI should keep its controls busy and keep polling. The
 * backend names the states, so anything it doesn't recognise as terminal counts
 * as active. Shared by the catalog store and the MCP Servers page so the two
 * can never disagree about what "busy" means.
 */
export function mcpOperationActive(state: string): boolean {
  return !SETTLED_OPERATIONS.includes(state.toLowerCase());
}

/**
 * Whether the entry has anything of its own in flight — a lifecycle operation, or a
 * reachability check. Anything that mutates it has to wait for both.
 *
 * The MCP Servers page used to keep a second set of busy ids beside this and OR the two
 * together, updated in the same tick the store patches the entry it describes. Two answers to
 * one question, and only the store's survived a refresh.
 */
export function mcpEntryBusy(
  server: Pick<McpCatalogServer, 'operationState' | 'checkStatus'>,
): boolean {
  return mcpOperationActive(server.operationState) || server.checkStatus === 'checking';
}

/** The state the UI shows an entry as while a verb of its own is in flight, before the backend
 *  has answered with the one it recorded. Named from the vocabulary above rather than spelled
 *  out again, so a state that stops existing fails here instead of silently never matching. */
export const IN_FLIGHT_STATE = {
  start: 'starting',
  stop: 'stopping',
  apply: 'applying',
} as const satisfies Record<'start' | 'stop' | 'apply', McpOperationState>;

/**
 * The entry already answering to `name`, or null when the name is free. Names
 * are compared trimmed and case-insensitively, and `selfId` excludes the entry
 * being edited — it is not its own duplicate.
 *
 * The editor greys out the save button with this and the store refuses the write
 * with it, so a name the form accepts is never one the store then rejects.
 */
export function duplicateCatalogName<T extends { id: string; name: string }>(
  name: string, servers: readonly T[], selfId?: string | null,
): T | null {
  const wanted = name.trim().toLowerCase();
  if (!wanted) return null;
  return servers.find(server =>
    server.id !== selfId && server.name.trim().toLowerCase() === wanted) ?? null;
}

/** Only plain http(s) endpoints, and nothing carrying credentials or a fragment. */
export function httpEndpointValid(value: string): boolean {
  try {
    const url = new URL(value);
    return (url.protocol === 'http:' || url.protocol === 'https:') && !!url.hostname
      && !url.username && !url.password && !url.hash;
  } catch {
    return false;
  }
}

/** A TCP port a managed entry can publish or listen on. */
export const mcpPortValid = (port: number | null): boolean =>
  !!port && port >= 1 && port <= 65_535;

/** The path a managed entry serves MCP on: absolute, single-slash, no fragment. */
export const mcpPathValid = (path: string): boolean =>
  path.startsWith('/') && !path.startsWith('//') && !path.includes('#');

/** A named volume, mounted where it can't escape upward — and never over the
 *  daemon socket. */
export function mcpVolumeValid(volume: McpNamedVolume): boolean {
  return VOLUME_NAME.test(volume.name.trim())
    && volume.target.trim().startsWith('/')
    && !volume.target.includes('/../')
    && !volume.target.endsWith('/..')
    && volume.target.trim() !== '/var/run/docker.sock';
}

export const mcpSupportServiceNameValid = (name: string): boolean =>
  SUPPORT_SERVICE_NAME.test(name.trim());

export function mcpHealthcheckValid(value: McpHealthcheck | null | undefined): boolean {
  if (!value) return true;
  if (!value.test.length || !HEALTHCHECK_VERBS.includes(value.test[0])) return false;
  if (value.test[0] === 'NONE' && value.test.length !== 1) return false;
  if ([value.interval, value.timeout, value.startPeriod]
      .some(item => !!item && !DURATION.test(item))) return false;
  return value.retries === null || value.retries === undefined
    || (value.retries >= 1 && value.retries <= 100);
}

/** A config entry as any caller can state it: the key, whether it is a secret,
 *  and whether a value is typed or already stored. */
export interface McpConfigEntryRule {
  key: string;
  value?: string | null;
  secret: boolean;
  set?: boolean;
}

/**
 * Every key is legal for its kind, none repeats, and a secret has a value —
 * either typed now or already stored. Header names collide case-insensitively;
 * environment keys do not.
 */
export function mcpConfigEntriesValid(
  entries: readonly McpConfigEntryRule[], kind: 'header' | 'env',
): boolean {
  const headers = kind === 'header';
  const pattern = headers ? HEADER_NAME : ENV_KEY;
  const seen = new Set<string>();
  return entries.every(entry => {
    const key = entry.key.trim();
    const identity = headers ? key.toLowerCase() : key;
    if (!key || !pattern.test(key) || seen.has(identity)) return false;
    seen.add(identity);
    return !entry.secret || !!entry.value || !!entry.set;
  });
}

/** The entry's address: the command for a stdio definition, and otherwise
 *  whichever URL the backend considers current. */
export function mcpDisplayEndpoint(server: McpCatalogServer): string {
  if (server.kind === 'stdio') return [server.stdioCommand, ...server.args].filter(Boolean).join(' ');
  return server.connectionUrl ?? server.url ?? server.crossHostUrl ?? 'endpoint pending';
}
