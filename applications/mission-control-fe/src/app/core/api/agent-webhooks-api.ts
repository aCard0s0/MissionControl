import { ApiOutboundWebhookRequest, ApiSubscribeWebhookRequest, ApiWebhooks } from './api-types';
import { AgentRef, agentPath } from './agent-ref';
import { ApiHttp, seg } from './http';

/**
 * `/api/agents/**\/webhooks` — a profile's inbound routes and the listener they arrive on.
 *
 * Mission Control manages these and never carries webhook traffic itself, so nothing here
 * is an endpoint a provider would call.
 *
 * Both directions live behind this one client: inbound routes wake the agent, outbound
 * targets are where the agent pushes signed lifecycle events. Outbound targets are addressed
 * by position — hermes gives them no id, and `name` is optional and not unique.
 */
export class AgentWebhooksApi {
  constructor(private readonly http: ApiHttp) {}

  list(ref: AgentRef): Promise<ApiWebhooks> {
    return this.http.get(this.path(ref));
  }

  /** Turns the profile's listener on or off. */
  setPlatform(
    ref: AgentRef, enabled: boolean, host?: string, port?: number,
  ): Promise<ApiWebhooks> {
    return this.http.put(`${this.path(ref)}/platform`, { enabled, host, port });
  }

  subscribe(ref: AgentRef, request: ApiSubscribeWebhookRequest): Promise<ApiWebhooks> {
    return this.http.post(this.path(ref), request);
  }

  addOutbound(ref: AgentRef, request: ApiOutboundWebhookRequest): Promise<ApiWebhooks> {
    return this.http.post(`${this.path(ref)}/outbound`, request);
  }

  updateOutbound(
    ref: AgentRef, index: number, request: ApiOutboundWebhookRequest,
  ): Promise<ApiWebhooks> {
    return this.http.put(`${this.path(ref)}/outbound/${index}`, request);
  }

  removeOutbound(ref: AgentRef, index: number): Promise<ApiWebhooks> {
    return this.http.delete(`${this.path(ref)}/outbound/${index}`);
  }

  /** The full HMAC secret, read only when an operator asks to see it. */
  secret(ref: AgentRef, route: string): Promise<{ secret: string }> {
    return this.http.get(`${this.route(ref, route)}/secret`);
  }

  test(ref: AgentRef, route: string): Promise<{ output: string }> {
    return this.http.post(`${this.route(ref, route)}/test`);
  }

  remove(ref: AgentRef, route: string): Promise<ApiWebhooks> {
    return this.http.delete(this.route(ref, route));
  }

  private path(ref: AgentRef): string {
    return `${agentPath(ref)}/webhooks`;
  }

  private route(ref: AgentRef, route: string): string {
    return `${this.path(ref)}/${seg(route)}`;
  }
}
