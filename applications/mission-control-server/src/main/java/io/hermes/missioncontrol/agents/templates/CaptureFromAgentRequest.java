package io.hermes.missioncontrol.agents.templates;

import jakarta.validation.constraints.NotBlank;

/** Snapshot a running agent's config into a new reusable template. */
public record CaptureFromAgentRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank String name,
    String templateName) {
}
