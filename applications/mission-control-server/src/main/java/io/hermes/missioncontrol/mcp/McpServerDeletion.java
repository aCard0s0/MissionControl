package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import org.springframework.stereotype.Service;

/**
 * Deleting a catalog record, including the cleanup every dependent module owes first.
 *
 * <p>The three steps and their order are the whole point of this class, and they were a
 * comment in {@code McpServersController}: take the record out of circulation for the deletion,
 * then let {@link AgentMcpCatalogService} release its references, then drop the row. The claim has to come first because the listeners' work is not undone — releasing a
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
 * <p>Its own bean rather than a method on {@link McpRegistryService}, which
 * {@link AgentMcpCatalogService} already depends on — injecting the cleanup there would close a
 * bean cycle. It reaches the {@code agents} service directly: there has only ever been one
 * thing holding copies of a catalog record, and a one-implementation listener interface bought
 * nothing but a level of indirection between the deletion and the only cleanup it runs.
 */
@Service
public class McpServerDeletion {

  private final McpRegistryService registry;
  private final AgentMcpCatalogService agentCopies;

  public McpServerDeletion(McpRegistryService registry, AgentMcpCatalogService agentCopies) {
    this.registry = registry;
    this.agentCopies = agentCopies;
  }

  /**
   * @return the record as it stands once the deletion is under way — already gone for a
   *     record with no containers, still present with {@code operation_state=deleting} for a
   *     managed one, whose Compose teardown runs on its own executor
   */
  public McpServerDto delete(String id) {
    registry.claimForDeletion(id);
    try {
      agentCopies.disableAndUnlinkForDeletion(id);
    } catch (RuntimeException cleanupFailed) {
      // cleanup that could not finish leaves what it already processed safely disabled and
      // retryable, but the record itself has to become usable again — otherwise one failed
      // delete strands it in `deleting` with nothing left to drive it out
      registry.releaseClaim(id);
      throw cleanupFailed;
    }
    return registry.completeDeletion(id);
  }
}
