package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.NotBlank;

public record AddSkillRequest(@NotBlank String name) {
}
