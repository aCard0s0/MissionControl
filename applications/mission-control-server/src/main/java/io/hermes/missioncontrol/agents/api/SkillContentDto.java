package io.hermes.missioncontrol.agents.api;

import java.util.List;

public record SkillContentDto(
    String name,
    String path,
    String body,
    List<String> files) {
}
