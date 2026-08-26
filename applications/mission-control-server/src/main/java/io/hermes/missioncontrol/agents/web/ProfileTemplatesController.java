package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.templates.CaptureFromAgentRequest;
import io.hermes.missioncontrol.agents.templates.DeployFromTemplateRequest;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateDto;
import io.hermes.missioncontrol.agents.templates.ProfileTemplateService;
import io.hermes.missioncontrol.agents.templates.UpsertProfileTemplateRequest;
import io.hermes.missioncontrol.docker.DockerHostRef;
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

/**
 * Reusable agent blueprints — dashboard-owned, applied when deploying agents.
 *
 * <p>Resolves its host through {@link AgentEndpoints} like every other controller in this
 * package, rather than reaching for {@code HostService} itself. That is what makes
 * {@link AgentEndpoints#linked} unavoidable: {@link #deploy} answers with an agent profile,
 * and one returned without its catalog links is both missing them and skipping the stranded-link
 * sweep every other profile read performs.
 */
@RestController
@RequestMapping("/api/profile-templates")
public class ProfileTemplatesController {

  private final ProfileTemplateService service;
  private final AgentEndpoints endpoints;

  public ProfileTemplatesController(ProfileTemplateService service, AgentEndpoints endpoints) {
    this.service = service;
    this.endpoints = endpoints;
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
    DockerHostRef host = endpoints.host(request.hostId());
    return service.captureFromAgent(
        host, request.containerId(), request.name(), request.templateName());
  }

  @PostMapping("/{id}/deploy")
  public AgentProfileDto deploy(
      @PathVariable String id, @Valid @RequestBody DeployFromTemplateRequest request) {
    DockerHostRef host = endpoints.host(request.hostId());
    return endpoints.linked(host, service.deploy(id, host, request.containerId(), request.name()));
  }

}
