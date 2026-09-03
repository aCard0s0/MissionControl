package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
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

  AgentSessionsController(HermesProfiles profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public List<SessionDto> list(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name) {
    return profiles.listSessions(host, containerId, name);
  }

  @GetMapping(value = "/{sessionId}", produces = MediaType.APPLICATION_JSON_VALUE)
  public String messages(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    // already a JSON array string emitted by the in-container query
    return profiles.readSessionMessages(
        host, containerId, name, sessionId);
  }

  @DeleteMapping("/{sessionId}")
  public void delete(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    profiles.deleteSession(host, containerId, name, sessionId);
  }
}
