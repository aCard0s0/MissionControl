package io.hermes.missioncontrol.agents.api;

/**
 * What a profile's gateway says about itself, from the two files it keeps for exactly that
 * purpose — {@code gateway_state.json} and the {@code ESTOP} sentinel.
 *
 * @param state the gateway's current state, {@code running} on a healthy profile
 * @param desiredState what it is heading for; different from {@code state} while it drains
 * @param activeAgents turns in flight right now — the readout that says whether stopping
 *     this container would drop live work
 * @param agentVersion the hermes version actually running, which is not the image tag
 * @param sessionStore health of the session store, {@code ok} when it opened cleanly
 * @param paused whether {@code hermes pause} has engaged the emergency stop
 * @param pauseReason the reason given to {@code hermes pause --reason}, or null
 * @param pausedAt when it was engaged, ISO-8601, or null
 */
public record GatewayDto(
    String state,
    String desiredState,
    int activeAgents,
    String agentVersion,
    String sessionStore,
    boolean paused,
    String pauseReason,
    String pausedAt) {

  /** The reading for a profile whose gateway has written nothing yet. */
  public static GatewayDto unknown() {
    return new GatewayDto("", "", 0, "", "", false, null, null);
  }
}
