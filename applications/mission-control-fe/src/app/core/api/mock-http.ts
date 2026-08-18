import { BoardColumn, DockerHost } from '../models';
import { ApiBoardTask } from './api-types';
import { seedTasks } from '../mock-data';
import { ApiHttp } from './http';

/**
 * `mock` data mode as a fake backend rather than a branch inside every store
 * slice.
 *
 * It stands in for {@link ApiHttp}, so in mock mode the resource clients under
 * this folder run exactly as they do against a real server — their paths, their
 * bodies, their error unwrapping — and each store slice keeps one code path
 * instead of a live half and a mock half that can drift apart.
 *
 * Domains move here one at a time. A path with no route throws the same shape a
 * real 404 would, so a slice that has not been converted yet fails loudly rather
 * than silently reading empty state; those slices still answer from their own
 * mock branch and never reach this class.
 */
export class MockHttp extends ApiHttp {
  private readonly world: MockWorld;

  constructor(dockerSocket: string) {
    super('');
    this.world = new MockWorld(dockerSocket);
  }

  override async req<T>(path: string, init?: RequestInit): Promise<T> {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    const route = ROUTES.find(r => r.method === method && r.match.test(path));
    if (!route) throw new Error(`mock backend has no route for ${method} ${path}`);
    const params = (r => r ? r.slice(1) : [])(route.match.exec(path));
    // a real call is never instantaneous, and a few operations are visibly slow;
    // the delay is what lets the UI show its own in-flight state in mock mode
    if (route.delayMs) await new Promise(resolve => setTimeout(resolve, route.delayMs));
    return route.handle(this.world, params, body) as T;
  }
}

/** Everything mock mode serves. One object, so a write through the API layer is
 *  visible to the next read the way a real backend's would be. */
class MockWorld {
  readonly hosts: DockerHost[];
  readonly tasks: ApiBoardTask[];

  private nextId = 0;

  constructor(dockerSocket: string) {
    this.hosts = [{
      id: 'dh-local', name: 'localhost', url: dockerSocket, kind: 'local',
      status: 'connected', engine: 'Docker 27.3', apiVersion: '1.47', latencyMs: 2, note: null,
    }];
    this.tasks = seedTasks();
  }

  id(prefix: string): string {
    return `${prefix}-mock-${this.nextId++}`;
  }
}

interface Route {
  method: string;
  match: RegExp;
  /** Fake latency before the response, in ms. */
  delayMs?: number;
  handle(world: MockWorld, params: string[], body: unknown): unknown;
}

/** How long a simulated daemon takes to answer a reachability probe. */
const PROBE_MS = 800;

/** A remote daemon that is reachable often, but not always — an operator should
 *  see what a bad address looks like without having to type one. */
const probe = (host: DockerHost): DockerHost => {
  const reachable = host.kind === 'local' || Math.random() > 0.15;
  return reachable
    ? {
        ...host, status: 'connected', engine: 'Docker 27.3', apiVersion: '1.47',
        latencyMs: host.kind === 'local' ? 2 : 18 + Math.floor(Math.random() * 90), note: null,
      }
    : {
        ...host, status: 'error', engine: null, apiVersion: null, latencyMs: null,
        note: 'connection refused — check the daemon address and TLS setup',
      };
};

const replace = (world: MockWorld, host: DockerHost): DockerHost => {
  const at = world.hosts.findIndex(h => h.id === host.id);
  if (at >= 0) world.hosts[at] = host;
  return host;
};

const ROUTES: Route[] = [
  {
    method: 'GET', match: /^\/api\/hosts$/,
    handle: world => [...world.hosts],
  },
  {
    method: 'POST', match: /^\/api\/hosts$/, delayMs: PROBE_MS,
    handle: (world, _params, body) => {
      const { name, url } = body as { name: string; url: string };
      const host: DockerHost = {
        id: world.id('dh'), name, url, kind: 'remote', status: 'connecting',
        engine: null, apiVersion: null, latencyMs: null, note: null,
      };
      world.hosts.push(host);
      return replace(world, probe(host));
    },
  },
  {
    method: 'POST', match: /^\/api\/hosts\/([^/]+)\/check$/, delayMs: PROBE_MS,
    handle: (world, [id]) => {
      const host = world.hosts.find(h => h.id === decodeURIComponent(id));
      if (!host) throw new Error(`no such host: ${id}`);
      return replace(world, probe(host));
    },
  },
  {
    method: 'DELETE', match: /^\/api\/hosts\/([^/]+)$/,
    handle: (world, [id]) => {
      const at = world.hosts.findIndex(h => h.id === decodeURIComponent(id));
      if (at < 0) throw new Error(`no such host: ${id}`);
      world.hosts.splice(at, 1);
      return undefined;
    },
  },
  {
    method: 'GET', match: /^\/api\/board\/tasks$/,
    handle: world => world.tasks.map(task => ({ ...task })),
  },
  {
    method: 'PATCH', match: /^\/api\/board\/tasks\/([^/]+)$/,
    handle: (world, [id], body) => {
      const task = world.tasks.find(t => t.id === decodeURIComponent(id));
      if (!task) throw new Error(`no such task: ${id}`);
      task.column = (body as { column: BoardColumn }).column;
      return undefined;
    },
  },
];
