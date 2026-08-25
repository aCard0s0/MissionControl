package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.Size;

/**
 * Why an agent was paused. Optional — hermes stores the reason in the sentinel and shows it
 * to anyone who messages the agent while it is held, so it is worth filling in, but a panic
 * button that demanded a justification first would be the wrong panic button.
 */
public record PauseAgentRequest(@Size(max = 200) String reason) {
}
