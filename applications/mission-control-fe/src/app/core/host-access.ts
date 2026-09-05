import { HostAccess } from './models';

/** What a deploy asks for when the operator opens nothing. */
export const NO_HOST_ACCESS: HostAccess = { ports: [], env: [], mounts: [] };

export const emptyAccess = (): HostAccess => ({ ports: [], env: [], mounts: [] });

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
      port(9119);
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
