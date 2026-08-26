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

  /**
   * The same profile with its MCP entries replaced — what catalog enrichment produces, since the
   * link overlay changes those entries and nothing else.
   *
   * <p>Here rather than in the enriching service, alongside {@link AgentMcpServerDto#linkedTo}
   * which does the same job one level down. As a private helper over there it was a second
   * positional copy of this field list, so adding a field to a profile meant editing a method
   * that never touched it.
   */
  public AgentProfileDto withMcp(List<AgentMcpServerDto> replacement) {
    return new AgentProfileDto(
        id, containerId, name, role, state, provider, model, apiKeyMasked, cwd, soul, memoryMd,
        configYaml, skills, List.copyOf(replacement), integrations, gateway, lastActive);
  }
}
