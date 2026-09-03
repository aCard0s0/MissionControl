package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Overlays the dashboard-owned catalog link onto each MCP entry a profile reports.
 *
 * <p>Reached from {@code HermesProfiles.readProfile}, which is the one place an
 * {@link AgentProfileDto} is built — so a profile cannot leave this application without it.
 * This used to be a public method on {@link AgentMcpCatalogService} that every controller
 * answering with a profile had to remember to call: fifteen call sites in six controllers
 * across three packages, two of which held that service for nothing else. The rule "a profile
 * leaves the API enriched" was therefore enforced by convention, and {@code McpGroupController}
 * did not enforce it — its group deploy answered with the profile
 * {@link AgentMcpCatalogService#connect} returned, whose freshly connected servers all read as
 * custom until the next poll. The same omission had already happened once before, on the
 * template deploy route.
 *
 * <p>Its own class rather than inlined into the profile facade because it is the one part of a
 * profile read that goes to SQLite rather than into the container, and because the sweep below
 * is a write.
 *
 * <p>Runs once per profile on every {@code /api/agents} listing, which the dashboard polls, so
 * it reads catalog rows through {@link McpRegistryService#definition} and never {@code live}:
 * the only field it needs is the current revision, and refreshing runtime state here meant one
 * {@code docker compose ps} — taken under the host's compose lock, the same one that serializes
 * provision/start/stop — per linked entry per profile per poll.
 *
 * <p>It is also where a link outliving what it described is dropped: one whose catalog entry is
 * gone, and one whose entry is no longer on the profile. Both are stranded rows nothing else
 * reaches — the second is what a removal leaves if its cleanup could not run, and the page has
 * no row left to offer an unlink on — and until they are gone
 * {@link AgentMcpCatalogService#assertCustom} refuses the alias to whatever is added under it
 * next.
 */
@Component
class CatalogLinkOverlay {

  private static final Logger log = LoggerFactory.getLogger(CatalogLinkOverlay.class);

  private final AgentMcpLinkRepository links;
  private final McpRegistryService registry;

  CatalogLinkOverlay(AgentMcpLinkRepository links, McpRegistryService registry) {
    this.links = links;
    this.registry = registry;
  }

  AgentProfileDto enrich(DockerHostRef host, AgentProfileDto profile) {
    Map<String, AgentMcpLink> byAlias = new HashMap<>();
    for (AgentMcpLink link : links.list(host.id(), profile.containerId(), profile.name())) {
      byAlias.put(link.alias(), link);
    }
    List<AgentMcpServerDto> servers = new ArrayList<>();
    for (AgentMcpServerDto server : profile.mcp()) {
      AgentMcpLink link = byAlias.remove(server.name());
      if (link == null) {
        servers.add(server);
        continue;
      }
      try {
        long currentRevision = registry.definition(link.serverId()).revision();
        servers.add(server.linkedTo(link.serverId(), link.syncedRevision(), currentRevision));
      } catch (NoSuchElementException deletedCatalogEntry) {
        links.delete(host.id(), profile.containerId(), profile.name(), server.name());
        servers.add(server);
      }
    }
    dropStrandedLinks(host, profile, byAlias.keySet());
    return profile.withMcp(servers);
  }

  /**
   * The links left over once every entry the profile reports has claimed its own.
   *
   * <p>Guarded on the profile carrying a {@code config.yaml} at all, because an empty read is
   * ambiguous: {@code HermesContainerFiles.readFile} cannot tell an unreadable file from an
   * absent one, and a profile whose config could not be read reports no MCP entries — which
   * would otherwise look exactly like a profile whose entries were all removed, and strand
   * nothing while dropping everything.
   */
  private void dropStrandedLinks(
      DockerHostRef host, AgentProfileDto profile, Set<String> aliases) {
    if (aliases.isEmpty() || profile.configYaml() == null || profile.configYaml().isBlank()) {
      return;
    }
    for (String alias : aliases) {
      log.info("dropping catalog link {} on agent {}: the entry is no longer in its config",
          alias, profile.name());
      links.delete(host.id(), profile.containerId(), profile.name(), alias);
    }
  }
}
