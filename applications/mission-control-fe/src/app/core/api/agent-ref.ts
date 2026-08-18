import { seg } from './http';

/**
 * Addresses one profile: the docker host, the container on it, and the profile
 * name inside that container. Every `/api/agents/**` endpoint is keyed by this
 * triple, so it travels as one value instead of three positional arguments.
 */
export interface AgentRef {
  hostId: string;
  containerId: string;
  name: string;
}

/** `/api/agents/{host}/{container}/{profile}` — every segment encoded. */
export const agentPath = (ref: AgentRef): string =>
  `/api/agents/${seg(ref.hostId)}/${seg(ref.containerId)}/${seg(ref.name)}`;
