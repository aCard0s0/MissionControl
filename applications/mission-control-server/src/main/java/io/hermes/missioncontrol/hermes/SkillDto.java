package io.hermes.missioncontrol.hermes;

public record SkillDto(
    String id,
    String name,
    String source,
    String version,
    String description,
    boolean enabled) {
}
