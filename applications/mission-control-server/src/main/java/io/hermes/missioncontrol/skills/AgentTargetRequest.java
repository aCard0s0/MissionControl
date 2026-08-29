package io.hermes.missioncontrol.skills;

import io.hermes.missioncontrol.agents.ProfileSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Which agent a deploy acts on.
 *
 * <p>Three parts because a profile name is only unique inside a container. {@code profile}
 * reaches a shell, so it carries the pattern here as well as being re-guarded by
 * {@code ProfilePaths} — the seam has to hold for callers that never came through a
 * controller.
 *
 * <p>Shared by both controllers in this package: a skill and a guide are deployed to an
 * agent the same way, and two copies of this would be two places to fix the day the
 * addressing changes.
 */
public record AgentTargetRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(regexp = ProfileSpec.NAME_PATTERN) String profile) {
}
