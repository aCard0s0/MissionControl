package io.hermes.missioncontrol.mcp;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Every value {@code mcp_servers.operation_state} may hold, and the one definition of which of
 * them mean nothing is in flight.
 *
 * <p>The column is {@code TEXT NOT NULL} with no default, so every value in it comes from this
 * package. It used to come from eight separate string literals, and "is it settled?" was asked
 * in five places in three spellings — twice as {@code List.of("idle", "error")}, once as
 * {@code "idle".equals(...)}, and on the frontend as a seven-name list, five of whose names
 * nothing here can produce. Adding a state meant finding all of them first.
 *
 * <p>Stored and sent as the lowercase name, so the column and the API keep the spelling they
 * already had. An unrecognised value counts as active on purpose: a row in a state this build
 * does not know is one it must not start a second operation on.
 */
enum McpOperationState {

  // ── in flight ──────────────────────────────────────────────────────────
  /** Creating the container for a record that has never had one. */
  PROVISIONING,
  /** Bringing a record back to its recorded desired state after a restart. */
  RECONCILING,
  STARTING,
  STOPPING,
  /** Rewriting the stack for a definition that changed under a running record. */
  APPLYING,
  DELETING,

  // ── settled ────────────────────────────────────────────────────────────
  IDLE,
  /** The last operation failed and said why in {@code operation_error}. Settled: a failure the
   *  operator can act on is not an operation still running. */
  ERROR;

  private static final Set<McpOperationState> SETTLED = EnumSet.of(IDLE, ERROR);

  /** As the column stores it and the API sends it. */
  String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  boolean isSettled() {
    return SETTLED.contains(this);
  }

  /** The state a stored value names, or null when this build does not know it. */
  static McpOperationState of(String stored) {
    if (stored == null) return null;
    String wanted = stored.toLowerCase(Locale.ROOT);
    for (McpOperationState state : values()) {
      if (state.wire().equals(wanted)) return state;
    }
    return null;
  }

  /** Whether a stored value means no operation is in flight. Unknown counts as in flight. */
  static boolean settled(String stored) {
    McpOperationState state = of(stored);
    return state != null && state.isSettled();
  }
}
