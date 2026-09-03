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

/**
 * The same triple in a request body, which is how a library deploy names the agent it is
 * aimed at — the target is not in the path there, because the path addresses the library row.
 *
 * The wire calls the profile `profile`, not `name`; four deploys wrote that rename out
 * themselves.
 */
export const agentTarget = (
  ref: AgentRef,
): { hostId: string; containerId: string; profile: string } => ({
  hostId: ref.hostId,
  containerId: ref.containerId,
  profile: ref.name,
});
