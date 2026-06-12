package io.hermes.missioncontrol.hermes;

import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agents")
public class AgentsController {

  private final HermesProfiles profiles;
  private final HermesSetup setup;
  private final HostService hosts;

  public AgentsController(HermesProfiles profiles, HermesSetup setup, HostService hosts) {
    this.profiles = profiles;
    this.setup = setup;
    this.hosts = hosts;
  }

  @GetMapping
  public List<AgentProfileDto> list(@RequestParam String hostId, @RequestParam String containerId) {
    DockerHostDto host = connected(hostId);
    return profiles.list(host.url(), containerId);
  }

  @PostMapping
  public AgentProfileDto create(@Valid @RequestBody CreateAgentRequest request) {
    DockerHostDto host = connected(request.hostId());
    return profiles.create(host.url(), request);
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}")
  public void delete(@PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    profiles.delete(host.url(), containerId, name);
  }

  @PutMapping("/{hostId}/{containerId}/{name}/soul")
  public void updateSoul(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateSoulRequest request) {
    DockerHostDto host = connected(hostId);
    profiles.updateSoul(host.url(), containerId, name, request.soul());
  }

  @PutMapping("/{hostId}/{containerId}/{name}/config")
  public AgentProfileDto updateConfig(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateConfigRequest request) {
    DockerHostDto host = connected(hostId);
    return profiles.updateConfig(host.url(), containerId, name, request.configYaml());
  }

  @PutMapping("/{hostId}/{containerId}/{name}/skills/{skillName}")
  public AgentProfileDto setSkillEnabled(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody SetSkillEnabledRequest request) {
    DockerHostDto host = connected(hostId);
    return profiles.setSkillEnabled(host.url(), containerId, name, skillName, request.enabled());
  }

  @PostMapping("/{hostId}/{containerId}/{name}/skills")
  public AgentProfileDto installSkill(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddSkillRequest request) {
    DockerHostDto host = connected(hostId);
    return profiles.installSkill(host.url(), containerId, name, request.name());
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}/skills/{skillName}")
  public AgentProfileDto uninstallSkill(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    DockerHostDto host = connected(hostId);
    return profiles.uninstallSkill(host.url(), containerId, name, skillName);
  }

  @PostMapping("/{hostId}/{containerId}/{name}/mcp")
  public AgentProfileDto addMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddMcpServerRequest request) {
    DockerHostDto host = connected(hostId);
    return profiles.addMcpServer(host.url(), containerId, name, request);
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}")
  public AgentProfileDto removeMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    DockerHostDto host = connected(hostId);
    return profiles.removeMcpServer(host.url(), containerId, name, serverName);
  }

  @GetMapping("/{hostId}/{containerId}/{name}/setup")
  public AgentSetupDto setup(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    return setup.setup(host.url(), containerId, name);
  }

  @PutMapping("/{hostId}/{containerId}/{name}/env")
  public AgentSetupDto putEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody SetEnvRequest request) {
    DockerHostDto host = connected(hostId);
    return setup.putEnv(host.url(), containerId, name, request.entries());
  }

  @PostMapping("/{hostId}/{containerId}/{name}/env/init")
  public AgentSetupDto initEnv(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    return setup.initEnv(host.url(), containerId, name);
  }

  @GetMapping("/{hostId}/{containerId}/{name}/integrations")
  public List<IntegrationDto> integrations(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    return profiles.integrations(host.url(), containerId, name);
  }

  private DockerHostDto connected(String hostId) {
    DockerHostDto host = hosts.check(hostId);
    if (!"connected".equals(host.status())) {
      throw new IllegalStateException("docker host not connected");
    }
    return host;
  }
}
