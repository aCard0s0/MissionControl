package io.hermes.missioncontrol.hermes;

import java.util.List;

/** A profile template as returned to the client (secrets masked). */
public record ProfileTemplateDto(
    String id,
    String name,
    String description,
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
