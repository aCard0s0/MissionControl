package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Deploy a template into a container as a new agent profile named {@code name}. */
public record DeployFromTemplateRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(
        regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*",
        message = "invalid profile name")
    String name) {
}
