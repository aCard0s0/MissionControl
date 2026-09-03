package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An agent's recorded conversations, read from its own SQLite store. */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/sessions")
class AgentSessionsController {

  private final HermesProfiles profiles;
  private final HostService hosts;

  AgentSessionsController(HermesProfiles profiles, HostService hosts) {
    this.profiles = profiles;
    this.hosts = hosts;
  }

  @GetMapping
  public List<SessionDto> list(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return profiles.listSessions(hosts.requireConnected(hostId), containerId, name);
  }

  @GetMapping(value = "/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public String messages(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    // already a JSON array string emitted by the in-container query
    return profiles.readSessionMessages(
        hosts.requireConnected(hostId), containerId, name, sessionId);
  }

  @DeleteMapping("/{sessionId}")
  public void delete(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    profiles.deleteSession(hosts.requireConnected(hostId), containerId, name, sessionId);
  }
}
