package io.hermes.missioncontrol.mcp;

import java.util.Locale;

/**
 * Every value {@code mcp_servers.runtime_state} may hold: what the record's container is
 * actually doing, as opposed to what it was last asked to do.
 *
 * <p>Declared for the same reason as {@link McpOperationState}, and with a live mismatch to show
 * for it: {@link #UNAVAILABLE} is written for every external and stdio record at creation and
 * never normalised afterwards, but the frontend's own list of runtime states did not contain it,
 * so its tolerant mapper silently rewrote every one of them to {@code unknown}. The vocabulary
 * being written in one place and read in another with no declaration in between is what let the
 * two drift without anything failing.
 */
enum McpRuntimeState {

  RUNNING,
  STOPPED,
  /** Managed, but the daemon has no container for it — never provisioned, or removed behind us. */
  MISSING,
  /** The container exists and the daemon reports it unhealthy. */
  ERROR,
  /** Not a kind that has a container at all: an external endpoint or a stdio definition. */
  UNAVAILABLE,
  /** Managed with a container the daemon would not tell us about. */
  UNKNOWN;

  /** As the column stores it and the API sends it. */
  String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * What a record of this kind starts life as: a managed one has no container yet, and nothing
   * else will ever have one.
   */
  static McpRuntimeState initial(boolean managed) {
    return managed ? MISSING : UNAVAILABLE;
  }

  /**
   * The state a container status reported by the daemon means here.
   *
   * <p>{@code unhealthy} becomes {@link #ERROR} because that is what it is for an MCP server:
   * the process is up but is failing its own healthcheck, so nothing can use it.
   */
  static McpRuntimeState fromContainerStatus(String status) {
    if (status == null) return UNKNOWN;
    if ("unhealthy".equalsIgnoreCase(status)) return ERROR;
    String wanted = status.toLowerCase(Locale.ROOT);
    for (McpRuntimeState state : values()) {
      if (state.wire().equals(wanted)) return state;
    }
    return UNKNOWN;
  }
}
