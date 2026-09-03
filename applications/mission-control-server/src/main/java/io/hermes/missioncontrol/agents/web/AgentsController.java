package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentLifecycle;
import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.ModelProviderRegistry;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.ContainerActivityDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.credentials.CredentialService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
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
import jakarta.validation.constraints.Size;

/**
 * Agent profiles themselves: the inventory, the lifecycle, and the documents that belong to
 * a profile as a whole.
 *
 * <p>The sub-resources each have their own controller on the same base path —
 * {@link AgentSkillsController}, {@link AgentMcpController}, {@link AgentSessionsController}
 * and {@link AgentSetupController} — and all of them resolve the host through
 * {@link HostService#requireConnected}, which refuses one that is not connected before any
 * container is touched.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentsController {

  private final HermesProfiles profiles;
  private final ProfileTemplateService templates;
  private final AgentLifecycle lifecycle;
  private final HostService hosts;
  private final AgentMcpCatalogService mcpCatalog;
  private final CredentialService credentials;

  public AgentsController(
      HermesProfiles profiles,
      ProfileTemplateService templates,
      AgentLifecycle lifecycle,
      HostService hosts,
      AgentMcpCatalogService mcpCatalog,
      CredentialService credentials) {
    this.profiles = profiles;
    this.templates = templates;
    this.lifecycle = lifecycle;
    this.hosts = hosts;
    this.mcpCatalog = mcpCatalog;
    this.credentials = credentials;
  }

  @GetMapping
  public List<AgentProfileDto> list(@RequestParam String hostId, @RequestParam String containerId) {
    DockerHostRef host = hosts.requireConnected(hostId);
    return profiles.list(host, containerId).stream()
        .map(profile -> mcpCatalog.enrich(host, profile))
        .toList();
  }

  @PostMapping
  public AgentProfileDto create(@Valid @RequestBody CreateAgentRequest request) {
    DockerHostRef host = hosts.requireConnected(request.hostId());
    ProfileSpec spec = ProfileSpec.from(request, apiKey(request));
    String templateId = request.fromTemplateId();
    if (templateId != null && !templateId.isBlank()) {
      // Create the request-configured base and layer the template's
      // soul/memory/skills/mcp/secrets as one owned, rollback-safe operation.
      return mcpCatalog.enrich(host, templates.createFromTemplate(templateId, host, spec));
    }
    return mcpCatalog.enrich(host, profiles.create(host, spec));
  }

  /**
   * The key to write into the new profile's {@code .env}: a saved credential's value when the
   * dialog picked one, the typed value otherwise.
   *
   * <p>The variable is resolved from the chosen provider here rather than sent by the client —
   * a credential id names which values may be read, and letting the caller also name the key
   * would let it read any of them.
   */
  private String apiKey(CreateAgentRequest request) {
    String credentialId = request.apiKeyCredentialId();
    if (credentialId == null || credentialId.isBlank()) return request.apiKey();
    String envVar = ModelProviderRegistry.envVar(request.provider());
    if (envVar == null) {
      // an OAuth provider, or one this build does not know: there is no variable to fill, so a
      // picked credential has nowhere to go and silently dropping it creates a keyless profile
      throw new IllegalArgumentException(
          "provider '" + request.provider() + "' takes no API key");
    }
    return credentials.valueFor(credentialId, envVar);
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}")
  public void delete(
      @PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    lifecycle.delete(hosts.requireConnected(hostId), containerId, name);
  }

  @PutMapping("/{hostId}/{containerId}/{name}/soul")
  public void updateSoul(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateSoulRequest request) {
    profiles.updateSoul(hosts.requireConnected(hostId), containerId, name, request.soul());
  }

  @PutMapping("/{hostId}/{containerId}/{name}/config")
  public AgentProfileDto updateConfig(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateConfigRequest request) {
    DockerHostRef host = hosts.requireConnected(hostId);
    return mcpCatalog.enrich(host, profiles.updateConfig(
        host, containerId, name, request.configYaml()));
  }

  /** What a stop, restart or replace of this container would interrupt. Read by the
   *  Containers page on the click, not by its poll — see {@link HermesProfiles#activity}. */
  @GetMapping("/{hostId}/{containerId}/activity")
  public ContainerActivityDto activity(
      @PathVariable String hostId, @PathVariable String containerId) {
    return profiles.activity(hosts.requireConnected(hostId), containerId);
  }

  @GetMapping("/{hostId}/{containerId}/{name}/integrations")
  public List<IntegrationDto> integrations(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return profiles.integrations(hosts.requireConnected(hostId), containerId, name);
  }

  /**
   * Hermes' own emergency stop, not a container stop: cron dispatch, kanban dispatch and new
   * gateway turns are held, and whatever is mid-turn is left to finish. Idempotent — pausing
   * an already-paused agent just restates the reason.
   */
  @PostMapping("/{hostId}/{containerId}/{name}/pause")
  public AgentProfileDto pause(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody(required = false) PauseAgentRequest request) {
    DockerHostRef host = hosts.requireConnected(hostId);
    return mcpCatalog.enrich(host, profiles.pause(
        host, containerId, name, request == null ? null : request.reason()));
  }

  @PostMapping("/{hostId}/{containerId}/{name}/resume")
  public AgentProfileDto resume(
      @PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    DockerHostRef host = hosts.requireConnected(hostId);
    return mcpCatalog.enrich(host, profiles.resume(host, containerId, name));
  }

  @GetMapping("/{hostId}/{containerId}/{name}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestParam(defaultValue = "100") int tail) {
    return profiles.logs(hosts.requireConnected(hostId), containerId, name, tail);
  }

  public record UpdateConfigRequest(String configYaml) {
  }

  public record UpdateSoulRequest(String soul) {
  }

  /**
   * Why an agent was paused. Optional — hermes stores the reason in the sentinel and shows it
   * to anyone who messages the agent while it is held, so it is worth filling in, but a panic
   * button that demanded a justification first would be the wrong panic button.
   */
  public record PauseAgentRequest(@Size(max = 200) String reason) {
  }
}
