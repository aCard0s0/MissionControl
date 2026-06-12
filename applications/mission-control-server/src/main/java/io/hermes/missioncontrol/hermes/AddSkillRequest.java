package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;

public record AddSkillRequest(@NotBlank String name) {
}
