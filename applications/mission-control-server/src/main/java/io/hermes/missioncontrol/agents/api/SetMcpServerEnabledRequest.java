package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.NotNull;

/** Enables or disables an MCP definition without removing its connection details. */
public record SetMcpServerEnabledRequest(@NotNull Boolean enabled) {
}
