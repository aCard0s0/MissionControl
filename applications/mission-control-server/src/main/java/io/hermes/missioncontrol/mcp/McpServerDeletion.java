package io.hermes.missioncontrol.mcp;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deleting a catalog record, including the cleanup every dependent module owes first.
 *
 * <p>The three steps and their order are the whole point of this class, and they were a
 * comment in {@code McpServersController}: ask whether the deletion may go ahead at all,
 * then let every {@link McpServerDeletionListener} release its references, then drop the
 * row. A refusal has to come first because the listeners' work is not undone — releasing a
 * reference rewrites {@code config.yaml} on every Agent holding the server — so running it
 * before {@link McpRegistryService#delete} could refuse would disable Agent copies for a
 * deletion that never happened.
 *
 * <p>Its own bean rather than a method on {@link McpRegistryService} because the listeners
 * are implemented by modules that already depend on the registry: injecting them there
 * closes a bean cycle. This is the same shape as {@code docker/ContainerUpdateService},
 * which joins the Docker upgrade to the dashboard rows that reference the container for the
 * same reason — the module that owns the operation must not learn about the modules that
 * hold references to it.
 */
@Service
public class McpServerDeletion {

  private final McpRegistryService registry;
  private final List<McpServerDeletionListener> listeners;

  public McpServerDeletion(McpRegistryService registry, List<McpServerDeletionListener> listeners) {
    this.registry = registry;
    this.listeners = listeners;
  }

  /**
   * @return the record as it stands once the deletion is under way — already gone for a
   *     record with no containers, still present with {@code operation_state=deleting} for a
   *     managed one, whose Compose teardown runs on its own executor
   */
  public McpServerDto delete(String id) {
    registry.assertDeletable(id);
    for (McpServerDeletionListener listener : listeners) {
      listener.beforeServerDeleted(id);
    }
    return registry.delete(id);
  }
}
