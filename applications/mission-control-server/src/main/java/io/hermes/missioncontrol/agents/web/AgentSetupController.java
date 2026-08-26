package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An agent's credentials and the setup report that merges them with {@code hermes status}. */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}")
class AgentSetupController {

  private final HermesSetup setup;
  private final HostService hosts;

  AgentSetupController(HermesSetup setup, HostService hosts) {
    this.setup = setup;
    this.hosts = hosts;
  }

  /** Container-level auth-provider status (e.g. Nous Portal OAuth login), read
   *  from the default profile's `hermes status`. OAuth tokens live at the
   *  container level (auth.json), so this reflects whether a newly-created agent
   *  on this container can reach providers like Nous before it even exists —
   *  surfaced in the create-agent modal. */
  @GetMapping("/auth-providers")
  public List<AuthProviderDto> authProviders(
      @PathVariable String hostId, @PathVariable String containerId) {
    return setup.setup(hosts.requireConnected(hostId), containerId, "default").authProviders();
  }

  @GetMapping("/{name}/setup")
  public AgentSetupDto setup(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return setup.setup(hosts.requireConnected(hostId), containerId, name);
  }

  @PutMapping("/{name}/env")
  public AgentSetupDto putEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody SetEnvRequest request) {
    return setup.putEnv(hosts.requireConnected(hostId), containerId, name, request.entries());
  }

  @PostMapping("/{name}/env/init")
  public AgentSetupDto initEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return setup.initEnv(hosts.requireConnected(hostId), containerId, name);
  }

  /** A batch of {@code .env} writes. A blank value removes the variable. */
  public record SetEnvRequest(@Valid List<@Valid EnvEntry> entries) {
  }
}
