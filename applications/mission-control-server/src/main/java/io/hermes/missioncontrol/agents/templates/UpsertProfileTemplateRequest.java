package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.secrets.SecretInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Create/update body for a profile template. */
public record UpsertProfileTemplateRequest(
    // deliberately its own expression, not ProfileSpec.NAME_PATTERN: a template is a
    // dashboard-owned record and its name never becomes a path inside a container
    @NotBlank @Pattern(
        regexp = "[a-zA-Z0-9][a-zA-Z0-9_.-]*",
        message = "invalid template name")
    String name,
    // a key into the client's glyph set, never rendered as markup. Bounded and
    // restricted to a slug so a stored value cannot carry anything else.
    @Size(max = 32) @Pattern(regexp = "|[a-z0-9-]+", message = "invalid icon")
    String icon,
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
