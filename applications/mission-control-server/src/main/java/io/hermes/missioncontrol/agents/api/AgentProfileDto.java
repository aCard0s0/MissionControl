package io.hermes.missioncontrol.agents.api;

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
    List<AgentMcpServerDto> mcp,
    List<IntegrationDto> integrations,
    GatewayDto gateway,
    long lastActive) {
}
