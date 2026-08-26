package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentLifecycle;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.CreateAgentRequest;
import io.hermes.missioncontrol.agents.api.ContainerActivityDto;
import io.hermes.missioncontrol.agents.api.IntegrationDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
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
 * {@link AgentEndpoints}.
 */
@RestController
@RequestMapping("/api/agents")
public class AgentsController {

  private final HermesProfiles profiles;
  private final ProfileTemplateService templates;
  private final AgentLifecycle lifecycle;
  private final AgentEndpoints endpoints;

  public AgentsController(
      HermesProfiles profiles,
      ProfileTemplateService templates,
      AgentLifecycle lifecycle,
      AgentEndpoints endpoints) {
    this.profiles = profiles;
    this.templates = templates;
    this.lifecycle = lifecycle;
    this.endpoints = endpoints;
  }

  @GetMapping
  public List<AgentProfileDto> list(@RequestParam String hostId, @RequestParam String containerId) {
    DockerHostRef host = endpoints.host(hostId);
    return profiles.list(host, containerId).stream()
        .map(profile -> endpoints.linked(host, profile))
        .toList();
  }

  @PostMapping
  public AgentProfileDto create(@Valid @RequestBody CreateAgentRequest request) {
    DockerHostRef host = endpoints.host(request.hostId());
    ProfileSpec spec = ProfileSpec.from(request);
    String templateId = request.fromTemplateId();
    if (templateId != null && !templateId.isBlank()) {
      // Create the request-configured base and layer the template's
      // soul/memory/skills/mcp/secrets as one owned, rollback-safe operation.
      return endpoints.linked(host, templates.createFromTemplate(templateId, host, spec));
    }
    return endpoints.linked(host, profiles.create(host, spec));
  }

  @DeleteMapping("/{hostId}/{containerId}/{name}")
  public void delete(
      @PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    lifecycle.delete(endpoints.host(hostId), containerId, name);
  }

  @PutMapping("/{hostId}/{containerId}/{name}/soul")
  public void updateSoul(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateSoulRequest request) {
    profiles.updateSoul(endpoints.host(hostId), containerId, name, request.soul());
  }

  @PutMapping("/{hostId}/{containerId}/{name}/config")
  public AgentProfileDto updateConfig(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody UpdateConfigRequest request) {
    DockerHostRef host = endpoints.host(hostId);
    return endpoints.linked(host, profiles.updateConfig(
        host, containerId, name, request.configYaml()));
  }

  /** What a stop, restart or replace of this container would interrupt. Read by the
   *  Containers page on the click, not by its poll — see {@link HermesProfiles#activity}. */
  @GetMapping("/{hostId}/{containerId}/activity")
  public ContainerActivityDto activity(
      @PathVariable String hostId, @PathVariable String containerId) {
    return profiles.activity(endpoints.host(hostId), containerId);
  }

  @GetMapping("/{hostId}/{containerId}/{name}/integrations")
  public List<IntegrationDto> integrations(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name) {
    return profiles.integrations(endpoints.host(hostId), containerId, name);
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
    DockerHostRef host = endpoints.host(hostId);
    return endpoints.linked(host, profiles.pause(
        host, containerId, name, request == null ? null : request.reason()));
  }

  @PostMapping("/{hostId}/{containerId}/{name}/resume")
  public AgentProfileDto resume(
      @PathVariable String hostId, @PathVariable String containerId, @PathVariable String name) {
    DockerHostRef host = endpoints.host(hostId);
    return endpoints.linked(host, profiles.resume(host, containerId, name));
  }

  @GetMapping("/{hostId}/{containerId}/{name}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestParam(defaultValue = "100") int tail) {
    return profiles.logs(endpoints.host(hostId), containerId, name, tail);
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
