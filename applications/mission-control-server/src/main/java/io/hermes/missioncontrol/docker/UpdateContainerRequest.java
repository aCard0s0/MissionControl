package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** @param version image tag to move the container onto */
public record UpdateContainerRequest(
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_][A-Za-z0-9._-]{0,127}", message = "invalid image tag")
    String version) {
}
