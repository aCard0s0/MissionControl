package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
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
 * <p>KNOWN GAP: the rare failure — the link write failing after the container write landed —
 * leaves a link row nothing reaches. {@code enrich} only visits aliases still present on the
 * profile, and for {@link #delete} a retry cannot get past {@code hermes profile delete} on a
 * profile that is already gone. Ordering does not fix that; making the cleanup retried or
 * idempotent does, the way {@code docker/ContainerUpdateService} handles the same class of
 * failure for the rows that reference a replaced container.
 *
 * <p>This is the {@code agents}-side counterpart of {@code mcp/McpServerDeletion} and
 * {@code docker/ContainerUpdateService}: one bean per protocol that spans two modules, so the
 * module that owns each half does not have to learn about the other.
 */
@Service
public class AgentLifecycle {

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
    mcpCatalog.deleteAgentLinks(host, containerId, name);
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
    mcpCatalog.forgetLink(host, containerId, name, serverName);
    return mcpCatalog.enrich(host, updated);
  }
}
