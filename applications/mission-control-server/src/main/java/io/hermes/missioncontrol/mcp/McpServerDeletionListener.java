package io.hermes.missioncontrol.mcp;

/**
 * Cleanup a dependent module performs before a catalog server row is deleted.
 *
 * <p>Deleting a catalog entry has to reach whatever else holds a copy of it — today
 * the Agent profiles linked to it. Declaring the hook here instead of injecting the
 * Agent service directly keeps the dependency pointing one way: {@code agents} knows
 * about the catalog, the catalog knows only this contract.
 */
public interface McpServerDeletionListener {

  /**
   * Releases every reference the listener holds to {@code serverId}.
   *
   * <p>Called after the registry has confirmed the deletion may go ahead and before
   * the row is dropped. Throwing aborts the deletion, so an implementation must leave
   * whatever it already processed in a state that is safe to retry.
   */
  void beforeServerDeleted(String serverId);
}
