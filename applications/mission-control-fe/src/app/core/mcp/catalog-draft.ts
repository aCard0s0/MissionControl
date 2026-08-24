import {
  duplicateCatalogName, httpEndpointValid, mcpConfigEntriesValid, mcpHealthcheckValid,
  mcpPathValid, mcpPortValid, mcpSupportServiceNameValid, mcpVolumeValid,
} from './catalog-rules';
import {
  McpCatalogKind, McpCatalogServer, McpCatalogServerInput, McpConfigEntry, McpHealthcheck,
  McpNamedVolume, McpTransport, McpSupportService,
} from '../models';

// A catalog entry as it is being edited: what a draft is, how a stored server becomes one,
// and what the backend gets on save. Pure functions, so every rule here is testable without
// rendering anything.
//
// What makes an entry legal is not this module's to decide — those rules live next door in
// catalog-rules and are the same ones the store enforces on save. What is left here is which
// of them apply to which kind, which is domain policy rather than a form's business.
//
// Here rather than in pages/ for that reason, and for a sharper one: mcpDraftToInput is the
// outbound half of the wire mapping whose inbound half is toMcpCatalogServer in
// core/store/wire-mappers. The two used to sit in different layers, which is how the
// absent-vs-empty rule for a support service's entrypoint and command came to be written
// down on one side of the round trip and not the other.

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
  /**
   * True for a service the catalog already stores under this name, whose name the
   * editor must therefore not let anyone change: the backend derives the Compose
   * service name from it, which is the hostname the server reaches the dependency
   * by and the prefix of its volumes, so a rename is a different service with
   * empty volumes. The backend refuses one; this is what keeps the form from
   * offering it. Never sent — it is a fact about the draft, not a field.
   */
  stored: boolean;
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
      // a duplicate is a new entry: nothing is stored behind its dependencies yet,
      // so their names are still the operator's to choose
      stored: !duplicate,
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
    // Every field named rather than spread: `stored` is the editor's own and must not
    // reach the wire, and `entrypoint`/`command` have to pass through exactly as they
    // arrived — absent when the service never named one, because an empty list is a
    // Compose override that clears the image's own. Same rule as `toSupportService`
    // on the way in.
    supportServices: managed ? draft.supportServices.map(service => ({
      name: service.name.trim(),
      image: service.image.trim(),
      platform: service.platform,
      entrypoint: service.entrypoint,
      command: service.command,
      environment: service.environment.map(entry => ({
        key: entry.key, value: entry.value ?? '', secret: entry.secret,
        clear: entry.clear,
      })),
      volumes: service.volumes.map(volume => ({ ...volume })),
      healthcheck: service.healthcheck,
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
  if (duplicateCatalogName(draft.name, existing, draft.id)) return false;
  if (!mcpConfigEntriesValid(draft.environment, 'env')
      || !mcpConfigEntriesValid(draft.headers, 'header')) return false;
  if (draft.kind === 'managed') return managedValid(draft);
  if (draft.kind === 'external') return httpEndpointValid(draft.url.trim());
  return !!draft.stdioCommand.trim();
}

function managedValid(draft: McpEditorDraft): boolean {
  if (!draft.hostId || !draft.image.trim() || !mcpPortValid(draft.internalPort)) return false;
  if (draft.publishedPort !== null && !mcpPortValid(draft.publishedPort)) return false;
  if (!mcpPathValid(draft.path)) return false;
  if (draft.crossHostUrl.trim() && !httpEndpointValid(draft.crossHostUrl.trim())) return false;
  if (draft.volumes.some(volume => !mcpVolumeValid(volume))) return false;
  if (!mcpHealthcheckValid(draft.healthcheck)) return false;
  const names = new Set<string>();
  for (const service of draft.supportServices) {
    const name = service.name.trim();
    if (!mcpSupportServiceNameValid(name) || names.has(name) || !service.image.trim()) return false;
    names.add(name);
    if (!mcpConfigEntriesValid(service.environment, 'env')
        || service.volumes.some(volume => !mcpVolumeValid(volume))
        || !mcpHealthcheckValid(service.healthcheck ?? null)) return false;
  }
  return true;
}

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
    environment: [], volumes: [], healthcheck: null, stored: false,
  };
}
