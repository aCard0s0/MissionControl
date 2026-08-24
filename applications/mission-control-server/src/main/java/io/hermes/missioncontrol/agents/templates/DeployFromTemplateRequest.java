package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.ProfileSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Deploy a template into a container as a new agent profile named {@code name}. */
public record DeployFromTemplateRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(
        regexp = ProfileSpec.NAME_PATTERN,
        message = "invalid profile name")
    String name) {
}
