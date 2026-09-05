import { HermesContainer, HostAccess, PortMapping } from './models';

/** What a deploy asks for when the operator opens nothing. */
export const NO_HOST_ACCESS: HostAccess = { ports: [], env: [], mounts: [] };

export const emptyAccess = (): HostAccess => ({ ports: [], env: [], mounts: [] });

export const hasAccess = (a: HostAccess): boolean =>
  a.ports.length + a.env.length + a.mounts.length > 0;

/** The port hermes' own web UI listens on — the dashboard preset's, and the one the card links to. */
export const HERMES_DASHBOARD_PORT = 9119;

/**
 * Where a browser reaches a published port. A loopback bind is reachable from the docker host
 * alone, so its address is kept as bound and the caller says so; an all-interfaces bind takes the
 * daemon's own name — a remote host's, or for the local socket the name this page was reached by,
 * which is the same machine.
 */
export function publishedUrl(port: PortMapping, hostUrl: string, pageHost: string): string {
  const ip = port.hostIp.trim();
  const everyInterface = !ip || ip === '0.0.0.0' || ip === '::';
  const remote = /^tcp:\/\/([^:/]+)/.exec(hostUrl)?.[1];
  return `http://${everyInterface ? remote ?? pageHost : ip}:${port.hostPort}`;
}

/** The address of hermes' own dashboard on this container, or null while nothing publishes it —
 *  a stopped container included, since there is nothing listening to send anyone to. */
export function dashboardUrl(c: HermesContainer, hostUrl: string, pageHost: string): string | null {
  if (c.status === 'stopped') return null;
  const port = c.published.find(p => p.containerPort === HERMES_DASHBOARD_PORT);
  return port ? publishedUrl(port, hostUrl, pageHost) : null;
}

export type HostAccessPreset = 'dashboard' | 'api-server' | 'webhook' | 'repo';

/**
 * The Hermes features a container cannot offer until something is opened to the host, each
 * as the rows that open it. The values come from hermes' own Docker guide; the ports are its
 * defaults, and every one binds loopback until the operator says otherwise.
 */
export const HOST_ACCESS_PRESETS: readonly { id: HostAccessPreset; label: string; hint: string }[] = [
  { id: 'dashboard', label: 'hermes dashboard',
    hint: 'Hermes\u2019 own web UI on 9119, behind a generated password' },
  { id: 'api-server', label: 'API server',
    hint: 'the OpenAI-compatible endpoint on 8642 with a generated key — also what hermes peer talks to. '
      + 'Work sent here runs unsandboxed in the container; keep it on 127.0.0.1' },
  { id: 'webhook', label: 'webhook listener',
    hint: 'publishes 8644, so inbound routes are reachable once a profile enables its listener' },
  { id: 'repo', label: 'repository mount',
    hint: 'a host directory the agent may read and write' },
];

/** 32 hex characters from the browser\u2019s CSPRNG, shown in the row so the operator can copy it. */
export const randomSecret = (): string =>
  Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');

/** The rows a preset needs, added to what is there. A port or variable already present is
 *  kept as the operator has it — pressing a chip twice changes nothing. */
export function applyPreset(access: HostAccess, preset: HostAccessPreset, secret = randomSecret): HostAccess {
  const ports = [...access.ports];
  const env = [...access.env];
  const mounts = [...access.mounts];
  const port = (containerPort: number) => {
    if (!ports.some(p => p.containerPort === containerPort)) {
      ports.push({ containerPort, hostPort: containerPort, hostIp: '127.0.0.1' });
    }
  };
  const set = (key: string, value: string) => {
    if (!env.some(e => e.key === key)) env.push({ key, value });
  };
  switch (preset) {
    case 'dashboard':
      port(HERMES_DASHBOARD_PORT);
      set('HERMES_DASHBOARD', '1');
      set('HERMES_DASHBOARD_BASIC_AUTH_USERNAME', 'operator');
      set('HERMES_DASHBOARD_BASIC_AUTH_PASSWORD', secret());
      break;
    case 'api-server':
      port(8642);
      set('API_SERVER_ENABLED', 'true');
      // hermes binds the API server to loopback inside the container; a published port
      // reaches nothing unless it listens on every interface
      set('API_SERVER_HOST', '0.0.0.0');
      set('API_SERVER_KEY', secret());
      break;
    case 'webhook':
      port(8644);
      break;
    case 'repo':
      mounts.push({ source: '', target: '/work', readOnly: false });
      break;
  }
  return { ports, env, mounts };
}

/** What is sent: a row the operator never finished is theirs, not a request. */
export function compactAccess(access: HostAccess): HostAccess {
  return {
    ports: access.ports.filter(p => p.containerPort > 0 && p.hostPort > 0)
      .map(p => ({ ...p, hostIp: p.hostIp.trim() })),
    env: access.env.filter(e => e.key.trim()).map(e => ({ key: e.key.trim(), value: e.value })),
    mounts: access.mounts.filter(m => m.source.trim() && m.target.trim())
      .map(m => ({ ...m, source: m.source.trim(), target: m.target.trim() })),
  };
}
