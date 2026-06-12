package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAgentRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(
        regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*",
        message = "invalid profile name")
    String name,
    @NotBlank String provider,
    @NotBlank String model,
    String apiKey,
    String cloneFrom,
    String baseUrl) {
}
