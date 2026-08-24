package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.secrets.SecretRef;
import java.util.List;

/** A profile template as returned to the client (secrets masked). */
public record ProfileTemplateDto(
    String id,
    String name,
    String description,
    String category,
    String provider,
    String model,
    String baseUrl,
    String cwd,
    String soul,
    String memory,
    List<String> skills,
    List<McpServerSpec> mcpServers,
    List<SecretRef> secrets,
    long createdAt,
    long updatedAt) {
}
