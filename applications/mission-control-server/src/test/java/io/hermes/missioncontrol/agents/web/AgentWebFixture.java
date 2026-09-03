package io.hermes.missioncontrol.agents.web;

import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;

/**
 * Shared setup for the {@code /api/agents/**} controller tests.
 *
 * <p>All five controllers resolve their host through {@link HostService#requireConnected}, so
 * every test file needs the same stub and the same profile fixture. The catalog overlay these
 * used to stub as well is now part of the profile read itself — see
 * {@code agents/CatalogLinkOverlay} — so a controller answering with a profile has nothing
 * left to remember.
 */
final class AgentWebFixture {

  static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///var/run/docker.sock");
  static final String CONTAINER = "c1";
  static final String PROFILE = "scout";
  static final String BASE = "/api/agents/" + HOST.id() + "/" + CONTAINER + "/" + PROFILE;

  private AgentWebFixture() {}

  static void hostIsConnected(HostService hosts) {
    when(hosts.requireConnected(HOST.id())).thenReturn(HOST);
  }

  static void hostIsDown(HostService hosts) {
    when(hosts.requireConnected(HOST.id()))
        .thenThrow(new UpstreamUnavailableException("docker host not connected"));
  }

  static AgentProfileDto profile(String name) {
    return new AgentProfileDto(
        HOST + "/" + CONTAINER + "/" + name, CONTAINER, name, "scout the codebase", "ready",
        "anthropic", "claude-opus-5", "sk-…abcd", "/work", "soul", "memory", "model: opus\n",
        List.of(), List.of(), List.of(), GatewayDto.unknown(), 1_700_000_000_000L);
  }
}
