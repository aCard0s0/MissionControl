package io.hermes.missioncontrol.agents.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;

/**
 * Shared setup for the {@code /api/agents/**} controller tests.
 *
 * <p>All five controllers resolve their host through {@link AgentEndpoints} and answer with a
 * profile enriched by {@link AgentMcpCatalogService}, so every test file needs the same two
 * stubs and the same profile fixture.
 */
final class AgentWebFixture {

  static final String HOST = "dh-local";
  static final String URL = "unix:///var/run/docker.sock";
  static final String CONTAINER = "c1";
  static final String PROFILE = "scout";
  static final String BASE = "/api/agents/" + HOST + "/" + CONTAINER + "/" + PROFILE;

  private AgentWebFixture() {}

  static void hostIsConnected(HostService hosts) {
    when(hosts.requireConnected(HOST)).thenReturn(new DockerHostDto(
        HOST, "localhost", URL, "local", "connected", "docker", "1.47", 3L, null));
  }

  static void hostIsDown(HostService hosts) {
    when(hosts.requireConnected(HOST))
        .thenThrow(new UpstreamUnavailableException("docker host not connected"));
  }

  /** Makes {@code AgentEndpoints.linked} transparent so a test can assert on what the service returned. */
  static void enrichmentIsTransparent(AgentMcpCatalogService mcpCatalog) {
    when(mcpCatalog.enrich(eq(HOST), any())).thenAnswer(invocation -> invocation.getArgument(1));
  }

  static AgentProfileDto profile(String name) {
    return new AgentProfileDto(
        HOST + "/" + CONTAINER + "/" + name, CONTAINER, name, "scout the codebase", "ready",
        "anthropic", "claude-opus-5", "sk-…abcd", "/work", "soul", "memory", "model: opus\n",
        List.of(), List.of(), List.of(), 1_700_000_000_000L);
  }
}
