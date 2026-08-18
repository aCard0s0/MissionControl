package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AddSkillRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.SetSkillEnabledRequest;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.agents.api.UpdateSkillContentRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** An agent's skills. Every mutation answers with the whole refreshed profile. */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/skills")
class AgentSkillsController {

  private final HermesProfiles profiles;
  private final AgentEndpoints endpoints;

  AgentSkillsController(HermesProfiles profiles, AgentEndpoints endpoints) {
    this.profiles = profiles;
    this.endpoints = endpoints;
  }

  @PutMapping("/{skillName}")
  public AgentProfileDto setEnabled(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody SetSkillEnabledRequest request) {
    return endpoints.linked(hostId, profiles.setSkillEnabled(
        endpoints.url(hostId), containerId, name, skillName, request.enabled()));
  }

  @PostMapping
  public AgentProfileDto install(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddSkillRequest request) {
    return endpoints.linked(hostId, profiles.installSkill(
        endpoints.url(hostId), containerId, name, request.name()));
  }

  @DeleteMapping("/{skillName}")
  public AgentProfileDto uninstall(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    return endpoints.linked(hostId, profiles.uninstallSkill(
        endpoints.url(hostId), containerId, name, skillName));
  }

  @GetMapping("/{skillName}/content")
  public SkillContentDto content(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName) {
    return profiles.readSkillContent(endpoints.url(hostId), containerId, name, skillName);
  }

  @PutMapping("/{skillName}/content")
  public AgentProfileDto updateContent(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String skillName,
      @RequestBody UpdateSkillContentRequest request) {
    return endpoints.linked(hostId, profiles.updateSkillContent(
        endpoints.url(hostId), containerId, name, skillName, request.body()));
  }
}
