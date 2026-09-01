package io.hermes.missioncontrol.agents.api;

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
 * <p>Shared by every library that deploys onto an agent — a skill, a guide, an MCP group.
 * They are all addressed the same way, and a copy per package would be several places to fix
 * the day the addressing changes. It lives here, beside the DTOs those packages already share,
 * rather than in whichever one happened to need it first.
 */
public record AgentTargetRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(regexp = ProfileSpec.NAME_PATTERN) String profile) {
}
