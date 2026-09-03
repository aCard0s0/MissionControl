package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.templates.DeployFromTemplateRequest;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.agents.templates.UpsertProfileTemplateRequest;
import io.hermes.missioncontrol.docker.DockerHostRef;
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
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.NotBlank;

/** Reusable agent blueprints — dashboard-owned, applied when deploying agents. */
@RestController
@RequestMapping("/api/profile-templates")
public class ProfileTemplatesController {

  private final ProfileTemplateService service;
  private final HostService hosts;

  public ProfileTemplatesController(ProfileTemplateService service, HostService hosts) {
    this.service = service;
    this.hosts = hosts;
  }

  @GetMapping
  public List<ProfileTemplateDto> list() {
    return service.list();
  }

  @GetMapping("/{id}")
  public ProfileTemplateDto get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  public ProfileTemplateDto create(@Valid @RequestBody UpsertProfileTemplateRequest request) {
    return service.create(request);
  }

  @PutMapping("/{id}")
  public ProfileTemplateDto update(
      @PathVariable String id, @Valid @RequestBody UpsertProfileTemplateRequest request) {
    return service.update(id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    service.delete(id);
  }

  @PostMapping("/capture")
  public ProfileTemplateDto capture(@Valid @RequestBody CaptureFromAgentRequest request) {
    DockerHostRef host = hosts.requireConnected(request.hostId());
    return service.captureFromAgent(
        host, request.containerId(), request.name(), request.templateName());
  }

  @PostMapping("/{id}/deploy")
  public AgentProfileDto deploy(
      @PathVariable String id, @Valid @RequestBody DeployFromTemplateRequest request) {
    DockerHostRef host = hosts.requireConnected(request.hostId());
    return service.deploy(id, host, request.containerId(), request.name());
  }

  /** Snapshot a running agent's config into a new reusable template. */
  public record CaptureFromAgentRequest(
      @NotBlank String hostId,
      @NotBlank String containerId,
      @NotBlank String name,
      String templateName) {
  }
}
