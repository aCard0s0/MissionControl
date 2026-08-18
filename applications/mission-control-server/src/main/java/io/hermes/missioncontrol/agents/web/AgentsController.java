package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AddSkillRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SetEnvRequest;
import io.hermes.missioncontrol.agents.api.SetMcpServerEnabledRequest;
import io.hermes.missioncontrol.agents.api.SetSkillEnabledRequest;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.UpdateConfigRequest;
import io.hermes.missioncontrol.agents.api.UpdateSkillContentRequest;
import io.hermes.missioncontrol.agents.api.UpdateSoulRequest;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.docker.LogLineDto;
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
  private final ProfileTemplateService templates;
  private final AgentMcpCatalogService mcpCatalog;

  public AgentsController(
      HermesProfiles profiles,
      HermesSetup setup,
      HostService hosts,
      ProfileTemplateService templates,
      AgentMcpCatalogService mcpCatalog) {
    this.profiles = profiles;
    this.setup = setup;
    this.hosts = hosts;
    this.templates = templates;
    this.mcpCatalog = mcpCatalog;
  }

  @GetMapping
  public List<AgentProfileDto> list(@RequestParam String hostId, @RequestParam String containerId) {
    DockerHostDto host = connected(hostId);
    return profiles.list(host.url(), containerId).stream()
        .map(profile -> mcpCatalog.enrich(hostId, profile))
        .toList();
  }

  @PostMapping
  public AgentProfileDto create(@Valid @RequestBody CreateAgentRequest request) {
    DockerHostDto host = connected(request.hostId());
    String templateId = request.fromTemplateId();
    if (templateId != null && !templateId.isBlank()) {
      // Create the request-configured base and layer the template's
      // soul/memory/skills/mcp/secrets as one owned, rollback-safe operation.
      return linked(request.hostId(), templates.createFromTemplate(templateId, host.url(), request));
    }
    return linked(request.hostId(), profiles.create(host.url(), request));
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}")
  public void delete(@PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    profiles.delete(host.url(), containerId, name);
    mcpCatalog.deleteAgentLinks(hostId, containerId, name);
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
    return linked(hostId, profiles.updateConfig(host.url(), containerId, name, request.configYaml()));
  }

  @PutMapping("/{hostId}/{containerId}/{name}/skills/{skillName}")
  public AgentProfileDto setSkillEnabled(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody SetSkillEnabledRequest request) {
    DockerHostDto host = connected(hostId);
    return linked(hostId, profiles.setSkillEnabled(host.url(), containerId, name, skillName, request.enabled()));
  }

  @PostMapping("/{hostId}/{containerId}/{name}/skills")
  public AgentProfileDto installSkill(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddSkillRequest request) {
    DockerHostDto host = connected(hostId);
    return linked(hostId, profiles.installSkill(host.url(), containerId, name, request.name()));
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}/skills/{skillName}")
  public AgentProfileDto uninstallSkill(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    DockerHostDto host = connected(hostId);
    return linked(hostId, profiles.uninstallSkill(host.url(), containerId, name, skillName));
  }

  @GetMapping("/{hostId}/{containerId}/{name}/skills/{skillName}/content")
  public SkillContentDto skillContent(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    DockerHostDto host = connected(hostId);
    return profiles.readSkillContent(host.url(), containerId, name, skillName);
  }

  @PutMapping("/{hostId}/{containerId}/{name}/skills/{skillName}/content")
  public AgentProfileDto updateSkillContent(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody UpdateSkillContentRequest request) {
    DockerHostDto host = connected(hostId);
    return linked(hostId, profiles.updateSkillContent(host.url(), containerId, name, skillName, request.body()));
  }

  @PostMapping("/{hostId}/{containerId}/{name}/mcp")
  public AgentProfileDto addMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddMcpServerRequest request) {
    DockerHostDto host = connected(hostId);
    mcpCatalog.assertCustom(hostId, containerId, name, request.name());
    return linked(hostId, profiles.addMcpServer(host.url(), containerId, name, request));
  }

  /** Replaces a custom MCP definition in one config write. The body name may
   * differ from {@code serverName}, in which case this is an atomic rename. */
  @PutMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}")
  public AgentProfileDto updateMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName,
      @Valid @RequestBody AddMcpServerRequest request) {
    DockerHostDto host = connected(hostId);
    mcpCatalog.assertCustom(hostId, containerId, name, serverName);
    return linked(hostId, profiles.updateMcpServer(host.url(), containerId, name, serverName, request));
  }

  /** Disconnect/reconnect is deliberately separate from permanent deletion so
   * every transport-specific setting remains available for a later reconnect. */
  @PutMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}/enabled")
  public AgentProfileDto setMcpEnabled(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName,
      @Valid @RequestBody SetMcpServerEnabledRequest request) {
    DockerHostDto host = connected(hostId);
    return linked(hostId, profiles.setMcpServerEnabled(
        host.url(), containerId, name, serverName, request.enabled()));
  }

  /** Permanently forgets the saved definition. Use the enabled endpoint for a
   * non-destructive disconnect. */
  @DeleteMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}")
  public AgentProfileDto removeMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    DockerHostDto host = connected(hostId);
    AgentProfileDto updated = profiles.removeMcpServer(host.url(), containerId, name, serverName);
    mcpCatalog.forgetLink(hostId, containerId, name, serverName);
    return linked(hostId, updated);
  }

  @PostMapping("/{hostId}/{containerId}/{name}/mcp/catalog")
  public AgentProfileDto connectCatalogMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody ConnectCatalogMcpRequest request) {
    connected(hostId);
    return mcpCatalog.connect(hostId, containerId, name, request);
  }

  @PostMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}/sync")
  public AgentProfileDto syncCatalogMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    connected(hostId);
    return mcpCatalog.sync(hostId, containerId, name, serverName);
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}/link")
  public AgentProfileDto unlinkCatalogMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    connected(hostId);
    return mcpCatalog.unlink(hostId, containerId, name, serverName);
  }

  @PostMapping("/{hostId}/{containerId}/{name}/mcp/{serverName}/test")
  public McpTestResult testMcp(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    DockerHostDto host = connected(hostId);
    return profiles.testMcpServer(host.url(), containerId, name, serverName);
  }

  /** Container-level auth-provider status (e.g. Nous Portal OAuth login), read
   *  from the default profile's `hermes status`. OAuth tokens live at the
   *  container level (auth.json), so this reflects whether a newly-created agent
   *  on this container can reach providers like Nous before it even exists —
   *  surfaced in the create-agent modal. */
  @GetMapping("/{hostId}/{containerId}/auth-providers")
  public List<AuthProviderDto> authProviders(
      @PathVariable String hostId, @PathVariable String containerId) {
    DockerHostDto host = connected(hostId);
    return setup.setup(host.url(), containerId, "default").authProviders();
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
      @Valid @RequestBody SetEnvRequest request) {
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

  @GetMapping("/{hostId}/{containerId}/{name}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestParam(defaultValue = "100") int tail) {
    DockerHostDto host = connected(hostId);
    return profiles.logs(host.url(), containerId, name, tail);
  }

  @GetMapping("/{hostId}/{containerId}/{name}/sessions")
  public List<SessionDto> sessions(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    DockerHostDto host = connected(hostId);
    return profiles.listSessions(host.url(), containerId, name);
  }

  @GetMapping(value = "/{hostId}/{containerId}/{name}/sessions/{sessionId}",
      produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
  public String sessionMessages(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    DockerHostDto host = connected(hostId);
    // already a JSON array string emitted by the in-container query
    return profiles.readSessionMessages(host.url(), containerId, name, sessionId);
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}/sessions/{sessionId}")
  public void deleteSession(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String sessionId) {
    DockerHostDto host = connected(hostId);
    profiles.deleteSession(host.url(), containerId, name, sessionId);
  }

  private DockerHostDto connected(String hostId) {
    return hosts.requireConnected(hostId);
  }

  private AgentProfileDto linked(String hostId, AgentProfileDto profile) {
    return mcpCatalog.enrich(hostId, profile);
  }
}
