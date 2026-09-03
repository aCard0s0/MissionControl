package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.constraints.NotBlank;

/** An agent's skills. Every mutation answers with the whole refreshed profile. */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/skills")
class AgentSkillsController {

  private final HermesProfiles profiles;

  AgentSkillsController(HermesProfiles profiles) {
    this.profiles = profiles;
  }

  @PutMapping("/{skillName}")
  public AgentProfileDto setEnabled(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody SetSkillEnabledRequest request) {
    return profiles.setSkillEnabled(
        host, containerId, name, skillName, request.enabled());
  }

  @PostMapping
  public AgentProfileDto install(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddSkillRequest request) {
    return profiles.installSkill(
        host, containerId, name, request.name());
  }

  @DeleteMapping("/{skillName}")
  public AgentProfileDto uninstall(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    return profiles.uninstallSkill(
        host, containerId, name, skillName);
  }

  @GetMapping("/{skillName}/content")
  public SkillContentDto content(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    return profiles.readSkillContent(host, containerId, name, skillName);
  }

  @PutMapping("/{skillName}/content")
  public AgentProfileDto updateContent(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody UpdateSkillContentRequest request) {
    return profiles.updateSkillContent(
        host, containerId, name, skillName, request.body());
  }

  public record SetSkillEnabledRequest(boolean enabled) {
  }

  public record UpdateSkillContentRequest(String body) {
  }

  public record AddSkillRequest(@NotBlank String name) {
  }
}
