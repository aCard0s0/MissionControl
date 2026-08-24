package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.secrets.SecretInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Create/update body for a profile template. */
public record UpsertProfileTemplateRequest(
    @NotBlank @Pattern(
        regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*",
        message = "invalid template name")
    String name,
    String description,
    @Size(max = 60) String category,
    String provider,
    String model,
    String baseUrl,
    String cwd,
    String soul,
    String memory,
    List<String> skills,
    List<McpServerSpec> mcpServers,
    List<SecretInput> secrets) {
}
