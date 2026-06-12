import { BoardColumn, DockerHost } from './models';

// Typed client for mission-control-server. apiBaseUrl '' = same origin
// (the combined image); a non-empty base supports split deployments.

export interface ApiContainer {
  id: string;
  shortId: string;
  name: string;
  hostId: string;
  status: 'running' | 'stopped' | 'unhealthy' | 'unknown';
  image: string;
  version: string;
  startedAt: number | null;
  sizeRootFsGb: number | null;
  profiles: string[];
}

export interface ApiStats {
  cpuPercent: number;
  ramMb: number;
  ramTotalMb: number;
  rxBytes: number;
  txBytes: number;
  sampledAt: number;
}

export interface ApiLogLine {
  ts: number;
  level: 'info' | 'warn' | 'error' | 'debug';
  source: string;
  msg: string;
}

export interface ApiBoardTask {
  id: string;
  containerId: string;
  agentId: string | null;
  title: string;
  column: BoardColumn;
  priority: 'low' | 'med' | 'high';
  tags: string[];
  createdAt: number;
}

export class HermesApi {
  private readonly base: string;

  constructor(apiBaseUrl: string) {
    this.base = apiBaseUrl.replace(/\/+$/, '');
  }

  private async req<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(this.base + path, {
      headers: { 'Content-Type': 'application/json' },
      ...init,
    });
    if (!res.ok) {
      let detail = `${res.status}`;
      try {
        const body = await res.json();
        if (body?.error) detail = body.error;
      } catch { /* non-json error body */ }
      throw new Error(detail);
    }
    const text = await res.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  health(): Promise<{ status: string; version: string; dockerConnected: boolean }> {
    return this.req('/health');
  }

  hosts(): Promise<DockerHost[]> {
    return this.req('/api/hosts');
  }

  addHost(name: string, url: string): Promise<DockerHost> {
    return this.req('/api/hosts', { method: 'POST', body: JSON.stringify({ name, url }) });
  }

  checkHost(id: string): Promise<DockerHost> {
    return this.req(`/api/hosts/${id}/check`, { method: 'POST' });
  }

  deleteHost(id: string): Promise<void> {
    return this.req(`/api/hosts/${id}`, { method: 'DELETE' });
  }

  containers(): Promise<ApiContainer[]> {
    return this.req('/api/containers');
  }

  stats(hostId: string, id: string): Promise<ApiStats> {
    return this.req(`/api/containers/${hostId}/${id}/stats`);
  }

  logs(hostId: string, id: string, tail = 100): Promise<ApiLogLine[]> {
    return this.req(`/api/containers/${hostId}/${id}/logs?tail=${tail}`);
  }

  deploy(hostId: string, name: string, version: string, profiles: string[]): Promise<{ id: string }> {
    return this.req('/api/containers', {
      method: 'POST',
      body: JSON.stringify({ hostId, name, version, profiles }),
    });
  }

  startContainer(hostId: string, id: string): Promise<void> {
    return this.req(`/api/containers/${hostId}/${id}/start`, { method: 'POST' });
  }

  stopContainer(hostId: string, id: string): Promise<void> {
    return this.req(`/api/containers/${hostId}/${id}/stop`, { method: 'POST' });
  }

  removeContainer(hostId: string, id: string): Promise<void> {
    return this.req(`/api/containers/${hostId}/${id}`, { method: 'DELETE' });
  }

  boardTasks(): Promise<ApiBoardTask[]> {
    return this.req('/api/board/tasks');
  }

  moveTask(id: string, column: BoardColumn): Promise<void> {
    return this.req(`/api/board/tasks/${id}`, { method: 'PATCH', body: JSON.stringify({ column }) });
  }
}
