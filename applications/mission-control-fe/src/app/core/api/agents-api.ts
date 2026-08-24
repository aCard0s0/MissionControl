import {
  ApiAgentProfile, ApiAgentSetup, ApiAuxiliaryModel, ApiChatMessage, ApiIntegration, ApiLogLine,
  ApiSession, ApiSetupAuthProvider,
} from './api-types';
import { AgentCronApi } from './agent-cron-api';
import { AgentMcpApi } from './agent-mcp-api';
import { AgentRef, agentPath } from './agent-ref';
import { AgentSkillsApi } from './agent-skills-api';
import { AgentWebhooksApi } from './agent-webhooks-api';
import { ApiHttp, seg } from './http';

/** A new profile's model wiring. `cloneFrom`/`fromTemplateId` seed its files. */
export interface CreateAgentRequest {
  hostId: string;
  containerId: string;
  name: string;
  provider: string;
  model: string;
  apiKey: string;
  cloneFrom?: string;
  baseUrl?: string;
  fromTemplateId?: string;
  auxiliary?: ApiAuxiliaryModel;
}

/**
 * `/api/agents` — profiles inside an Agent container. Skills, MCP servers and
 * scheduled jobs and webhooks are large enough surfaces of their own to live on
 * {@link AgentsApi.skills}, {@link AgentsApi.mcp}, {@link AgentsApi.cron} and
 * {@link AgentsApi.webhooks}.
 */
export class AgentsApi {
  readonly skills: AgentSkillsApi;
  readonly mcp: AgentMcpApi;
  readonly cron: AgentCronApi;
  readonly webhooks: AgentWebhooksApi;

  constructor(private readonly http: ApiHttp) {
    this.skills = new AgentSkillsApi(http);
    this.mcp = new AgentMcpApi(http);
    this.cron = new AgentCronApi(http);
    this.webhooks = new AgentWebhooksApi(http);
  }

  list(hostId: string, containerId: string): Promise<ApiAgentProfile[]> {
    return this.http.get(`/api/agents?hostId=${seg(hostId)}&containerId=${seg(containerId)}`);
  }

  create(request: CreateAgentRequest): Promise<ApiAgentProfile> {
    return this.http.post('/api/agents', request);
  }

  remove(ref: AgentRef): Promise<void> {
    return this.http.delete(agentPath(ref));
  }

  /** Profile-scoped supervised gateway log — unlike docker logs these lines
   *  carry an authoritative profile identity. */
  logs(ref: AgentRef, tail = 100): Promise<ApiLogLine[]> {
    return this.http.get(`${agentPath(ref)}/logs?tail=${tail}`);
  }

  updateSoul(ref: AgentRef, soul: string): Promise<void> {
    return this.http.put(`${agentPath(ref)}/soul`, { soul });
  }

  updateConfig(ref: AgentRef, configYaml: string): Promise<ApiAgentProfile> {
    return this.http.put(`${agentPath(ref)}/config`, { configYaml });
  }

  integrations(ref: AgentRef): Promise<ApiIntegration[]> {
    return this.http.get(`${agentPath(ref)}/integrations`);
  }

  sessions(ref: AgentRef): Promise<ApiSession[]> {
    return this.http.get(`${agentPath(ref)}/sessions`);
  }

  sessionMessages(ref: AgentRef, sessionId: string): Promise<ApiChatMessage[]> {
    return this.http.get(`${agentPath(ref)}/sessions/${seg(sessionId)}`);
  }

  deleteSession(ref: AgentRef, sessionId: string): Promise<void> {
    return this.http.delete(`${agentPath(ref)}/sessions/${seg(sessionId)}`);
  }

  setup(ref: AgentRef): Promise<ApiAgentSetup> {
    return this.http.get(`${agentPath(ref)}/setup`);
  }

  /** Empty/null entry value removes that key from the profile's .env file. */
  setEnv(ref: AgentRef, entries: Array<{ key: string; value: string | null }>): Promise<ApiAgentSetup> {
    return this.http.put(`${agentPath(ref)}/env`, { entries });
  }

  /** Writes the commented-out .env template, only when the file is missing. */
  initEnv(ref: AgentRef): Promise<ApiAgentSetup> {
    return this.http.post(`${agentPath(ref)}/env/init`);
  }

  /** Container-level auth-provider status (e.g. Nous Portal OAuth) read from the
   *  default profile — usable before any agent exists, for the create modal. */
  authProviders(hostId: string, containerId: string): Promise<ApiSetupAuthProvider[]> {
    return this.http.get(`/api/agents/${seg(hostId)}/${seg(containerId)}/auth-providers`);
  }
}
