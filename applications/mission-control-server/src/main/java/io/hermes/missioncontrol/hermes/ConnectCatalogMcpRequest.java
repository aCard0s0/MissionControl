package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;

/** Connects a reusable catalog definition under an Agent-local alias. */
public record ConnectCatalogMcpRequest(
    @NotBlank String serverId,
    @NotBlank String alias) {}
