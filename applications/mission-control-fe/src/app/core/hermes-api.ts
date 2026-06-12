import { BoardColumn, DockerHost, ModelProvider, OllamaModel } from './models';

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

export interface ApiImageTags {
  repository: string;
  tags: string[];
}

export interface ApiSkillRef {
  id: string;
  name: string;
  source: 'bundled' | 'user' | 'hub' | string;
  version: string;
  description: string;
  enabled: boolean;
}

export interface ApiMcpServer {
  id: string;
  name: string;
  transport: 'stdio' | 'http' | 'sse' | string;
  status: 'connected' | 'error' | 'disabled' | string;
  tools: number;
  latencyMs: number | null;
}

export interface ApiIntegration {
  kind: string;
  status: 'up' | 'degraded' | 'down' | 'off' | string;
  detail: string;
}

export interface ApiModelCatalog {
  provider: string;
  models: string[];
  source: 'config' | 'live' | string;
}

export interface ApiPullState {
  model: string;
  status: 'pulling' | 'done' | 'error';
  detail: string | null;
}

export interface ApiAgentProfile {
  id: string;
  containerId: string;
  name: string;
  role: string;
  state: 'active' | 'idle' | 'dormant';
  provider: string;
  model: string;
  apiKeyMasked: string;
  cwd: string;
  soul: string;
  memoryMd: string;
  configYaml: string;
  skills: ApiSkillRef[];
  mcp: ApiMcpServer[];
  integrations: ApiIntegration[];
  lastActive: number;
}

export interface ApiSetupApiKey {
  label: string;
  envVar: string;
  set: boolean;
  masked: string | null;
}

export interface ApiSetupAuthProvider {
  label: string;
  ok: boolean;
  status: string;
  hint: string | null;
}

export interface ApiSetupKeyProvider {
  label: string;
  ok: boolean;
  status: string;
}

export interface ApiSetupMessaging {
  label: string;
  ok: boolean;
  status: string;
  tokenVar: string;
  homeVar: string | null;
  homeChannel: string | null;
}

export interface ApiAgentSetup {
  envPath: string;
  envExists: boolean;
  apiKeys: ApiSetupApiKey[];
  authProviders: ApiSetupAuthProvider[];
  apiKeyProviders: ApiSetupKeyProvider[];
  messaging: ApiSetupMessaging[];
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

  modelCatalog(provider: string): Promise<ApiModelCatalog> {
    return this.req(`/api/models/${encodeURIComponent(provider)}`);
  }

  modelCatalogLive(provider: string, apiKey: string): Promise<ApiModelCatalog> {
    return this.req(`/api/models/${encodeURIComponent(provider)}`, {
      method: 'POST',
      body: JSON.stringify({ apiKey }),
    });
  }

  modelProviders(): Promise<ModelProvider[]> {
    return this.req('/api/model-providers');
  }

  addModelProvider(name: string, url: string): Promise<ModelProvider> {
    return this.req('/api/model-providers', { method: 'POST', body: JSON.stringify({ name, url }) });
  }

  deleteModelProvider(id: string): Promise<void> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}`, { method: 'DELETE' });
  }

  checkModelProvider(id: string): Promise<ModelProvider> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}/check`, { method: 'POST' });
  }

  providerModels(id: string): Promise<OllamaModel[]> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}/models`);
  }

  pullProviderModel(id: string, name: string): Promise<void> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}/models/pull`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
  }

  pullStatus(id: string): Promise<ApiPullState[]> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}/pulls`);
  }

  deleteProviderModel(id: string, name: string): Promise<void> {
    return this.req(`/api/model-providers/${encodeURIComponent(id)}/models/delete`, {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
  }

  containers(): Promise<ApiContainer[]> {
    return this.req('/api/containers');
  }

  imageTags(hostId: string): Promise<ApiImageTags> {
    return this.req(`/api/images/tags?hostId=${encodeURIComponent(hostId)}`);
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

  agents(hostId: string, containerId: string): Promise<ApiAgentProfile[]> {
    return this.req(`/api/agents?hostId=${encodeURIComponent(hostId)}&containerId=${encodeURIComponent(containerId)}`);
  }

  createAgent(
    hostId: string,
    containerId: string,
    name: string,
    provider: string,
    model: string,
    apiKey: string,
    cloneFrom?: string,
    baseUrl?: string,
  ): Promise<ApiAgentProfile> {
    return this.req('/api/agents', {
      method: 'POST',
      body: JSON.stringify({ hostId, containerId, name, provider, model, apiKey, cloneFrom, baseUrl }),
    });
  }

  updateSoul(hostId: string, containerId: string, name: string, soul: string): Promise<void> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/soul`, {
      method: 'PUT',
      body: JSON.stringify({ soul }),
    });
  }

  updateAgentConfig(hostId: string, containerId: string, name: string, configYaml: string): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/config`, {
      method: 'PUT',
      body: JSON.stringify({ configYaml }),
    });
  }

  setSkillEnabled(hostId: string, containerId: string, name: string, skillName: string, enabled: boolean): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/skills/${encodeURIComponent(skillName)}`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    });
  }

  installSkill(hostId: string, containerId: string, name: string, skillId: string): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/skills`, {
      method: 'POST',
      body: JSON.stringify({ name: skillId }),
    });
  }

  uninstallSkill(hostId: string, containerId: string, name: string, skillName: string): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/skills/${encodeURIComponent(skillName)}`, {
      method: 'DELETE',
    });
  }

  addMcpServer(
    hostId: string,
    containerId: string,
    name: string,
    request: { name: string; transport: string; url?: string; command?: string; args?: string; enabled?: boolean },
  ): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/mcp`, {
      method: 'POST',
      body: JSON.stringify(request),
    });
  }

  removeMcpServer(hostId: string, containerId: string, name: string, serverName: string): Promise<ApiAgentProfile> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/mcp/${encodeURIComponent(serverName)}`, {
      method: 'DELETE',
    });
  }

  integrations(hostId: string, containerId: string, name: string): Promise<ApiIntegration[]> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/integrations`);
  }

  agentSetup(hostId: string, containerId: string, name: string): Promise<ApiAgentSetup> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/setup`);
  }

  setAgentEnv(
    hostId: string,
    containerId: string,
    name: string,
    entries: Array<{ key: string; value: string | null }>,
  ): Promise<ApiAgentSetup> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/env`, {
      method: 'PUT',
      body: JSON.stringify({ entries }),
    });
  }

  initAgentEnv(hostId: string, containerId: string, name: string): Promise<ApiAgentSetup> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}/env/init`, {
      method: 'POST',
    });
  }

  deleteAgent(hostId: string, containerId: string, name: string): Promise<void> {
    return this.req(`/api/agents/${encodeURIComponent(hostId)}/${encodeURIComponent(containerId)}/${encodeURIComponent(name)}`, {
      method: 'DELETE',
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
