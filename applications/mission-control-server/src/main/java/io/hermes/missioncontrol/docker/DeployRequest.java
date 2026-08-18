package io.hermes.missioncontrol.docker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * @param version image tag to deploy; null or blank means 'latest'. Constrained exactly
 *     like {@link UpdateContainerRequest#version()} — the same value reaches the same
 *     daemon, and without the rule a typo here is reported as a 502 daemon failure only
 *     after the managed volume has already been created.
 */
public record DeployRequest(
    @NotBlank String hostId,
    @NotBlank @Pattern(regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*", message = "invalid container name") String name,
    @Pattern(regexp = "|[A-Za-z0-9_][A-Za-z0-9._-]{0,127}", message = "invalid image tag") String version,
    @Size(max = 50) List<@Pattern(
        regexp = "default|[a-z0-9][a-z0-9_-]{0,63}",
        message = "invalid profile name") String> profiles) {
}
