package io.hermes.missioncontrol.agents.api;

public record SkillDto(
    String id,
    String name,
    String source,
    String version,
    String description,
    boolean enabled) {
}
