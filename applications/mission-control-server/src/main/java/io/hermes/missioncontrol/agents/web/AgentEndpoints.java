package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
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
 *
 * <p>{@link #host} is the whole of that resolution. It used to be two methods — one
 * handing back a bare url, one asserting connectedness for endpoints whose service
 * re-resolved the host itself from an id — which meant a single request could probe the
 * host and then resolve it again unprobed. A {@link DockerHostRef} carries both halves, so
 * there is one call and nothing left to re-resolve downstream.
 */
@Component
class AgentEndpoints {

  private final HostService hosts;
  private final AgentMcpCatalogService mcpCatalog;

  AgentEndpoints(HostService hosts, AgentMcpCatalogService mcpCatalog) {
    this.hosts = hosts;
    this.mcpCatalog = mcpCatalog;
  }

  /** The connected host. Throws before any container is touched. */
  DockerHostRef host(String hostId) {
    return hosts.requireConnected(hostId);
  }

  AgentProfileDto linked(DockerHostRef host, AgentProfileDto profile) {
    return mcpCatalog.enrich(host, profile);
  }
}
