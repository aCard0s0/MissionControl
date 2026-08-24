package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.List;

/**
 * A reusable agent blueprint, owned by the dashboard (stored in mission-control's
 * SQLite DB). Distinct from {@link AgentProfileDto}, which is a live agent instance
 * inside a container. A template can be applied when deploying a new agent.
 */
public record ProfileTemplate(
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
    List<StoredSecret> secrets,
    long createdAt,
    long updatedAt) {
}
