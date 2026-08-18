import {
  McpCatalogKind, McpCatalogServer, McpCatalogServerInput, McpConfigEntry, McpHealthcheck,
  McpNamedVolume, McpTransport, McpSupportService,
} from '../core/models';

// The MCP Servers editor, minus the UI: what a draft is, how a stored server
// becomes one, what makes it valid, and what the backend gets on save. Pure
// functions, so every rule here is testable without rendering the page.

/** A config entry being edited. A stored secret arrives with an empty `value`. */
export interface McpEditorEntry extends McpConfigEntry {
  value: string;
}

/** A support service being edited. The wire type leaves its lists optional; the
 *  editor always materializes them, so every "+ row" button can append to one
 *  without first having to create it. */
export interface McpEditorSupportService extends Omit<McpSupportService, 'environment' | 'volumes'> {
  environment: McpEditorEntry[];
  volumes: McpNamedVolume[];
}

/** The form state behind the editor panel — strings where the wire wants lists,
 *  because a textarea is what the operator actually types into. */
export interface McpEditorDraft {
  id: string | null;
  hostLocked: boolean;
  name: string;
  description: string;
  kind: McpCatalogKind;
  hostId: string;
  transport: McpTransport;
  url: string;
  image: string;
  platform: string;
  entrypoint: string;
  command: string;
  stdioCommand: string;
  args: string;
  internalPort: number | null;
  publishedPort: number | null;
  path: string;
  crossHostUrl: string;
  headers: McpEditorEntry[];
  environment: McpEditorEntry[];
  volumes: McpNamedVolume[];
  healthcheck: McpHealthcheck | null;
  supportServices: McpEditorSupportService[];
}

/** Defaults for a managed server's HTTP endpoint. */
const DEFAULT_INTERNAL_PORT = 1100;
const DEFAULT_PATH = '/mcp';

/** One argument per line — never shell-parsed, so a value may contain spaces. */
export function splitMcpLines(value: string): string[] {
  return value.split(/\r?\n/).map(item => item.trim()).filter(Boolean);
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

export function newMcpDraft(kind: McpCatalogKind, hostId: string): McpEditorDraft {
  return {
    id: null, hostLocked: false, name: '', description: '', kind,
    hostId, transport: kind === 'stdio' ? 'stdio' : 'http',
    url: '', image: '', platform: '', entrypoint: '', command: '', stdioCommand: '', args: '',
    internalPort: kind === 'managed' ? DEFAULT_INTERNAL_PORT : null, publishedPort: null,
    path: DEFAULT_PATH, crossHostUrl: '', headers: [], environment: [], volumes: [],
    healthcheck: null, supportServices: [],
  };
}

/** Re-applies what a kind switch decides, in place: the form binds to this same
 *  draft object, so it must be mutated rather than replaced. */
export function applyMcpKindDefaults(draft: McpEditorDraft): void {
  draft.transport = draft.kind === 'stdio' ? 'stdio' : 'http';
  if (draft.kind === 'managed') {
    draft.internalPort ??= DEFAULT_INTERNAL_PORT;
    draft.path ||= DEFAULT_PATH;
  }
}

/** Loads a stored server into the editor. `duplicate` starts a new entry from it,
 *  which cannot inherit encrypted values without a source link. */
export function mcpDraftFromServer(server: McpCatalogServer, duplicate = false): McpEditorDraft {
  const entries = (items: McpConfigEntry[]): McpEditorEntry[] => items.map(item => ({
    ...item,
    value: item.secret ? '' : (item.value ?? ''),
    set: duplicate && item.secret ? false : item.set,
    recoverable: duplicate && item.secret ? false : item.recoverable,
  }));
  return {
    id: duplicate ? null : server.id,
    hostLocked: !duplicate,
    name: duplicate ? `${server.name} copy` : server.name,
    description: server.description,
    kind: server.kind,
    hostId: server.hostId ?? '',
    transport: server.transport,
    url: server.url ?? '',
    image: server.image ?? '',
    platform: server.platform ?? '',
    entrypoint: server.entrypoint.join('\n'),
    command: server.command.join('\n'),
    stdioCommand: server.stdioCommand ?? '',
    args: server.args.join('\n'),
    internalPort: server.internalPort,
    publishedPort: server.publishedPort,
    path: server.path ?? DEFAULT_PATH,
    crossHostUrl: server.crossHostUrl ?? '',
    headers: entries(server.headers),
    environment: entries(server.environment),
    volumes: server.volumes.map(volume => ({ ...volume })),
    healthcheck: copyHealthcheck(server.healthcheck),
    supportServices: server.supportServices.map(service => ({
      ...service,
      environment: entries(service.environment ?? []),
      volumes: (service.volumes ?? []).map(volume => ({ ...volume })),
      healthcheck: copyHealthcheck(service.healthcheck),
    })),
  };
}

/** Kind decides which fields the backend accepts, so the rest are dropped rather
 *  than sent as leftovers from a kind the operator switched away from. */
export function mcpDraftToInput(draft: McpEditorDraft): McpCatalogServerInput {
  const managed = draft.kind === 'managed';
  const external = draft.kind === 'external';
  const stdio = draft.kind === 'stdio';
  return {
    name: draft.name.trim(), description: draft.description.trim(), kind: draft.kind,
    hostId: managed ? draft.hostId : null,
    transport: stdio ? 'stdio' : draft.transport,
    url: external ? draft.url.trim() : null,
    image: managed ? draft.image.trim() : null,
    platform: managed ? (draft.platform.trim() || null) : null,
    entrypoint: managed ? splitMcpLines(draft.entrypoint) : [],
    command: managed ? splitMcpLines(draft.command) : [],
    stdioCommand: stdio ? draft.stdioCommand.trim() : null,
    args: stdio ? splitMcpLines(draft.args) : [],
    internalPort: managed ? draft.internalPort : null,
    publishedPort: managed ? draft.publishedPort : null,
    path: managed ? draft.path.trim() : null,
    crossHostUrl: managed ? (draft.crossHostUrl.trim() || null) : null,
    headers: stdio ? [] : configEntries(draft.headers),
    environment: external ? [] : configEntries(draft.environment),
    volumes: managed ? namedVolumes(draft.volumes) : [],
    healthcheck: managed ? draft.healthcheck : null,
    supportServices: managed ? draft.supportServices.map(service => ({
      ...service,
      environment: service.environment.map(entry => ({
        key: entry.key, value: entry.value ?? '', secret: entry.secret,
        clear: entry.clear,
      })),
      volumes: service.volumes.map(volume => ({ ...volume })),
    })) : [],
  };
}

/**
 * Whether the draft can be saved. `existing` is the catalog it will join, so a
 * name already taken by another entry is refused here rather than by the
 * backend. Managed servers carry the most rules: they mint a Compose service.
 */
export function mcpDraftValid(draft: McpEditorDraft, existing: readonly McpCatalogServer[]): boolean {
  if (!draft.name.trim()) return false;
  if (existing.some(server =>
    server.id !== draft.id && server.name.toLowerCase() === draft.name.trim().toLowerCase())) return false;
  if (!entriesValid(draft.environment, false) || !entriesValid(draft.headers, true)) return false;
  if (draft.kind === 'managed') return managedValid(draft);
  if (draft.kind === 'external') return httpEndpointValid(draft.url.trim());
  return !!draft.stdioCommand.trim();
}

function managedValid(draft: McpEditorDraft): boolean {
  if (!draft.hostId || !draft.image.trim() || !portValid(draft.internalPort)) return false;
  if (draft.publishedPort !== null && !portValid(draft.publishedPort)) return false;
  if (!draft.path.startsWith('/') || draft.path.startsWith('//') || draft.path.includes('#')) return false;
  if (draft.crossHostUrl.trim() && !httpEndpointValid(draft.crossHostUrl.trim())) return false;
  if (draft.volumes.some(volume => !volumeValid(volume))) return false;
  if (!healthcheckValid(draft.healthcheck)) return false;
  const names = new Set<string>();
  for (const service of draft.supportServices) {
    const name = service.name.trim();
    if (!/^[a-z0-9][a-z0-9-]{0,62}$/.test(name) || names.has(name) || !service.image.trim()) return false;
    names.add(name);
    if (!entriesValid(service.environment, false)
        || service.volumes.some(volume => !volumeValid(volume))
        || !healthcheckValid(service.healthcheck ?? null)) return false;
  }
  return true;
}

/** Header names and environment keys have different legal alphabets, and neither
 *  may repeat. A secret is only valid once it has a value, stored or typed. */
function entriesValid(entries: McpEditorEntry[], headers: boolean): boolean {
  const pattern = headers
    ? /^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/
    : /^[A-Za-z_][A-Za-z0-9_]*$/;
  const seen = new Set<string>();
  return entries.every(entry => {
    const key = entry.key.trim();
    const identity = headers ? key.toLowerCase() : key;
    if (!key || !pattern.test(key) || seen.has(identity)) return false;
    seen.add(identity);
    return !entry.secret || !!entry.value || !!entry.set;
  });
}

/** A docker volume name, mounted at an absolute path that can't escape upward —
 *  and never over the daemon socket. */
function volumeValid(volume: McpNamedVolume): boolean {
  return /^[a-z0-9][a-z0-9_.-]{0,62}$/.test(volume.name.trim())
    && volume.target.trim().startsWith('/')
    && !volume.target.includes('/../')
    && !volume.target.endsWith('/..')
    && volume.target.trim() !== '/var/run/docker.sock';
}

function healthcheckValid(value: McpHealthcheck | null | undefined): boolean {
  if (!value) return true;
  if (!value.test.length || !['CMD', 'CMD-SHELL', 'NONE'].includes(value.test[0])) return false;
  if (value.test[0] === 'NONE' && value.test.length !== 1) return false;
  const duration = /^[1-9][0-9]*(?:\.[0-9]+)?(?:ns|us|ms|s|m|h)$/;
  if ([value.interval, value.timeout, value.startPeriod]
      .some(item => !!item && !duration.test(item))) return false;
  return value.retries === null || value.retries === undefined
    || (value.retries >= 1 && value.retries <= 100);
}

const portValid = (port: number | null): boolean => !!port && port >= 1 && port <= 65_535;

function configEntries(items: McpEditorEntry[]): McpConfigEntry[] {
  return items
    .filter(item => item.key.trim())
    .map(item => ({
      key: item.key.trim(), value: item.value, secret: item.secret,
      clear: item.clear,
    }));
}

function namedVolumes(volumes: McpNamedVolume[]): McpNamedVolume[] {
  return volumes
    .filter(volume => volume.name.trim() && volume.target.trim())
    .map(volume => ({ name: volume.name.trim(), target: volume.target.trim() }));
}

function copyHealthcheck(value: McpHealthcheck | null | undefined): McpHealthcheck | null {
  return value ? { ...value, test: [...value.test] } : null;
}

/** The healthcheck a fresh toggle starts from. */
export function defaultHealthcheck(): McpHealthcheck {
  return { test: ['CMD'], interval: '30s', timeout: '5s', retries: 3, startPeriod: '5s' };
}

// The blank rows the editor's "+ …" buttons append. A new row is recoverable and
// unset: nothing is stored behind it yet, so a secret must be typed to count.
export function blankConfigEntry(): McpEditorEntry {
  return { key: '', value: '', secret: false, set: false, recoverable: true };
}

export function blankVolume(): McpNamedVolume {
  return { name: '', target: '' };
}

export function blankSupportService(): McpEditorSupportService {
  return {
    name: '', image: '', platform: null, entrypoint: [], command: [],
    environment: [], volumes: [], healthcheck: null,
  };
}
