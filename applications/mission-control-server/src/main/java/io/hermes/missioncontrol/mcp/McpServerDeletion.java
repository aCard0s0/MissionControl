package io.hermes.missioncontrol.mcp;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Deleting a catalog record, including the cleanup every dependent module owes first.
 *
 * <p>The three steps and their order are the whole point of this class, and they were a
 * comment in {@code McpServersController}: take the record out of circulation for the deletion,
 * then let every {@link McpServerDeletionListener} release its references, then drop the
 * row. The claim has to come first because the listeners' work is not undone — releasing a
 * reference rewrites {@code config.yaml} on every Agent holding the server — so running it
 * before {@link McpRegistryService#completeDeletion} could refuse would disable Agent copies
 * for a deletion that never happened.
 *
 * <p>A claim, and not the question it used to be. Answering "nothing is in flight" left the
 * record free for as long as the listeners took, which is one {@code docker exec} per linked
 * Agent: a {@code start} arriving in that window was admitted, and the deletion then refused —
 * after the Agents had already been severed, with the link rows that recorded where their
 * entries came from gone too. Ordering cannot reach that; holding the record is what closes it,
 * so every path out of here that is not a completed deletion gives the claim back.
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
    registry.claimForDeletion(id);
    try {
      for (McpServerDeletionListener listener : listeners) {
        listener.beforeServerDeleted(id);
      }
    } catch (RuntimeException listenerFailed) {
      // a listener that could not finish leaves what it already processed safely disabled and
      // retryable, but the record itself has to become usable again — otherwise one failed
      // delete strands it in `deleting` with nothing left to drive it out
      registry.releaseClaim(id);
      throw listenerFailed;
    }
    return registry.completeDeletion(id);
  }
}
