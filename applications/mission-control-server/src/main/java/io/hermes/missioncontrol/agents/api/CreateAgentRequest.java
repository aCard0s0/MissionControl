package io.hermes.missioncontrol.agents.api;

import io.hermes.missioncontrol.agents.ProfileSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAgentRequest(
    @NotBlank String hostId,
    @NotBlank String containerId,
    @NotBlank @Pattern(
        regexp = ProfileSpec.NAME_PATTERN,
        message = "invalid profile name")
    String name,
    @NotBlank String provider,
    @NotBlank String model,
    String apiKey,
    /**
     * A saved credential to take {@code apiKey} from instead of typing it, resolved against the
     * chosen provider's variable. Wins over {@code apiKey} when both arrive — the dialog sends
     * one or the other, and preferring the id keeps a stale character in the text box from
     * beating an explicit pick.
     */
    @Size(max = 64) String apiKeyCredentialId,
    String cloneFrom,
    String baseUrl,
    String fromTemplateId,
    /** Optional — auxiliary side tasks follow the main model when absent. */
    AuxiliaryModelSpec auxiliary) {
}
