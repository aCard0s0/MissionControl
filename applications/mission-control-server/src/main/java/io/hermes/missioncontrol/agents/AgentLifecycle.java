package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The agent writes that span both sides of the split this application lives on: what hermes
 * owns inside the container, and what the dashboard owns in SQLite.
 *
 * <p>Both operations here used to be two consecutive calls in a controller, which left the
 * order — the part that decides what a half-completed delete leaves behind — as an
 * implementation detail of the HTTP layer, reachable from nowhere else and assertable only
 * through MockMvc. The order is unchanged and is the same in both: the container write, then
 * the SQLite one.
 *
 * <p>That order is right because the two halves do not fail at the same rate. The container
 * write is a {@code docker exec} over a socket, with a timeout, against a container that can
 * stop mid-call and a CLI that can refuse what it was asked. The link write is one local
 * statement against a single-writer SQLite file. Running the fragile half first means the
 * common failure writes nothing at all, which is consistent and retryable; reversing it would
 * buy a benign rare failure at the cost of an inconsistent common one.
 *
 * <p>The rare failure — the link write failing after the container write landed — is handled
 * rather than ordered away, because ordering cannot reach it. Three things cover it, and all
 * three are needed:
 *
 * <ul>
 *   <li>the cleanup is {@linkplain #cleanUp retried once} before it is given up on, since a
 *       brief write lock on a single-writer database is the realistic cause
 *   <li>a given-up cleanup does not fail the request, because the container write has already
 *       landed and reporting failure would say otherwise — the precedent is
 *       {@code docker/ContainerUpdateService}, which makes the same trade for the rows that
 *       reference a replaced container
 *   <li>what is left behind is reachable afterwards: {@link HermesProfiles#delete} tolerates a
 *       profile that is already gone so the delete can simply be retried, and
 *       {@link AgentMcpCatalogService#enrich} drops a link whose entry is no longer on the
 *       profile, so the agents listing heals a stranded one on its next poll
 * </ul>
 *
 * <p>This is the {@code agents}-side counterpart of {@code mcp/McpServerDeletion} and
 * {@code docker/ContainerUpdateService}: one bean per protocol that spans two modules, so the
 * module that owns each half does not have to learn about the other.
 */
@Service
public class AgentLifecycle {

  private static final Logger log = LoggerFactory.getLogger(AgentLifecycle.class);

  /** Long enough for a competing SQLite write to finish, short enough to sit in a request. */
  private static final long RETRY_DELAY_MS = 200;

  private final HermesProfiles profiles;
  private final AgentMcpCatalogService mcpCatalog;

  public AgentLifecycle(HermesProfiles profiles, AgentMcpCatalogService mcpCatalog) {
    this.profiles = profiles;
    this.mcpCatalog = mcpCatalog;
  }

  /**
   * Removes the profile from the container, then every catalog link the dashboard held for it.
   *
   * <p>The links are dashboard-owned rows keyed by profile name; leaving them behind would
   * resurrect MCP entries on a later profile that happens to reuse the name.
   */
  public void delete(DockerHostRef host, String containerId, String name) {
    profiles.delete(host, containerId, name);
    cleanUp("catalog links for agent " + name,
        () -> mcpCatalog.deleteAgentLinks(host, containerId, name));
  }

  /**
   * Permanently forgets one MCP entry: its definition in the profile's {@code config.yaml},
   * then its catalog link.
   *
   * <p>The link is dropped second, as in {@link #delete} and for the reason given there.
   * Dropping it first and then failing the profile write — the likelier of the two — would
   * also leave the entry in {@code config.yaml} with nothing recording where it came from.
   *
   * @return the profile as it stands afterwards, with catalog links overlaid
   */
  public AgentProfileDto removeMcpServer(
      DockerHostRef host, String containerId, String name, String serverName) {
    AgentProfileDto updated = profiles.removeMcpServer(host, containerId, name, serverName);
    cleanUp("catalog link " + serverName + " on agent " + name,
        () -> mcpCatalog.forgetLink(host, containerId, name, serverName));
    return mcpCatalog.enrich(host, updated);
  }

  /**
   * Runs a dashboard-side cleanup whose container-side half has already landed: once, then
   * once more, then logged and given up on rather than failing a request for work that
   * succeeded.
   */
  private void cleanUp(String what, Runnable cleanup) {
    try {
      cleanup.run();
      return;
    } catch (RuntimeException first) {
      try {
        Thread.sleep(RETRY_DELAY_MS);
        cleanup.run();
        return;
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        log.error("interrupted while removing {}", what, first);
        return;
      } catch (RuntimeException retried) {
        log.error("the agent was updated but {} could not be removed — the agents listing "
            + "drops a stranded link on its next read, and a repeated delete is safe", what,
            retried);
      }
    }
  }
}
