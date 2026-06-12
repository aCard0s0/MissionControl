package io.hermes.missioncontrol.hermes;

import java.util.List;

public record AgentProfileDto(
    String id,
    String containerId,
    String name,
    String role,
    String state,
    String provider,
    String model,
    String apiKeyMasked,
    String cwd,
    String soul,
    String memoryMd,
    String configYaml,
    List<SkillDto> skills,
    List<McpServerDto> mcp,
    List<IntegrationDto> integrations,
    long lastActive) {
}
