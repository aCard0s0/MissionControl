package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;

public record AddMcpServerRequest(
    @NotBlank String name,
    @NotBlank String transport,
    String url,
    String command,
    String args,
    Boolean enabled) {
}
