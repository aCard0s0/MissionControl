package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.hosts.HostService;
import org.springframework.stereotype.Component;

/**
 * The two things every agent endpoint does: resolve the Docker host from a path variable,
 * refusing one that is not connected, and enrich a profile with its MCP catalog links
 * before it goes out.
 *
 * <p>Shared by the controllers under {@code /api/agents/**} so the host check cannot be
 * forgotten on a new endpoint — reaching a container through an unconnected host is the
 * failure that check exists to turn into a 503 rather than a stack trace.
 */
@Component
class AgentEndpoints {

  private final HostService hosts;
  private final AgentMcpCatalogService mcpCatalog;

  AgentEndpoints(HostService hosts, AgentMcpCatalogService mcpCatalog) {
    this.hosts = hosts;
    this.mcpCatalog = mcpCatalog;
  }

  /** The daemon URL for a connected host. Throws before any container is touched. */
  String url(String hostId) {
    return hosts.requireConnected(hostId).url();
  }

  /** Asserts the host is reachable without needing its URL — for endpoints that delegate
   *  to a service holding its own host registry. */
  void requireConnected(String hostId) {
    hosts.requireConnected(hostId);
  }

  AgentProfileDto linked(String hostId, AgentProfileDto profile) {
    return mcpCatalog.enrich(hostId, profile);
  }
}
