package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.ManagedMcpStack;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDeletionListener;
import io.hermes.missioncontrol.mcp.McpServerDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Materializes catalog definitions into Hermes profile YAML while the catalog
 * link itself remains dashboard-owned SQLite state.
 */
@Service
public class AgentMcpCatalogService implements McpServerDeletionListener {

  private static final Pattern ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}");

  private final McpRegistryService registry;
  private final AgentMcpLinkRepository links;
  private final HostService hosts;
  private final DockerGateway docker;
  private final HermesProfiles profiles;

  public AgentMcpCatalogService(
      McpRegistryService registry,
      AgentMcpLinkRepository links,
      HostService hosts,
      DockerGateway docker,
      HermesProfiles profiles) {
    this.registry = registry;
    this.links = links;
    this.hosts = hosts;
    this.docker = docker;
    this.profiles = profiles;
  }

  public AgentProfileDto connect(
      DockerHostRef host, String containerId, String profile, ConnectCatalogMcpRequest request) {
    String alias = alias(request.alias());
    var source = registry.require(request.serverId());
    AgentProfileDto current = profiles.get(host, containerId, profile);
    if (current.mcp().stream().anyMatch(server -> alias.equals(server.name()))) {
      throw new ResourceConflictException("an MCP server named '" + alias + "' already exists on this Agent");
    }
    if ("managed".equals(source.kind()) && !"running".equals(source.runtimeState())) {
      throw new ResourceConflictException("managed MCP server is not running: " + source.name());
    }
    McpServerDefinition definition = materialize(host, containerId, source, alias, true);
    AgentProfileDto updated = profiles.addMcpServer(host, containerId, profile, definition);
    long now = System.currentTimeMillis();
    links.upsert(new AgentMcpLink(
        host.id(), containerId, profile, alias, source.id(), source.revision(), now, now));
    return enrich(host, updated);
  }

  public AgentProfileDto sync(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    String alias = alias(serverAlias);
    AgentMcpLink link = links.find(host.id(), containerId, profile, alias)
        .orElseThrow(() -> new NoSuchElementException("MCP entry is not linked to the catalog: " + alias));
    var source = registry.require(link.serverId());
    AgentProfileDto current = profiles.get(host, containerId, profile);
    AgentMcpServerDto existing = current.mcp().stream()
        .filter(server -> alias.equals(server.name()))
        .findFirst()
        .orElseThrow(() -> new NoSuchElementException("unknown MCP server on Agent: " + alias));
    McpServerDefinition definition = materialize(
        host, containerId, source, alias, existing.enabled());
    AgentProfileDto updated = profiles.updateMcpServer(
        host, containerId, profile, alias, definition);
    links.upsert(new AgentMcpLink(
        host.id(), containerId, profile, alias, source.id(), source.revision(),
        link.createdAt(), System.currentTimeMillis()));
    return enrich(host, updated);
  }

  public AgentProfileDto unlink(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    String alias = alias(serverAlias);
    if (links.find(host.id(), containerId, profile, alias).isEmpty()) {
      throw new NoSuchElementException("MCP entry is not linked to the catalog: " + alias);
    }
    links.delete(host.id(), containerId, profile, alias);
    return enrich(host, profiles.get(host, containerId, profile));
  }

  public void assertCustom(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    if (links.find(host.id(), containerId, profile, alias(serverAlias)).isPresent()) {
      throw new ResourceConflictException(
          "catalog-linked MCP entries must be customized before direct editing");
    }
  }

  public void forgetLink(
      DockerHostRef host, String containerId, String profile, String serverAlias) {
    links.delete(host.id(), containerId, profile, alias(serverAlias));
  }

  public void deleteAgentLinks(DockerHostRef host, String containerId, String profile) {
    // one statement rather than one per alias: a failure partway through the old loop
    // left the profile holding some of its links and not others
    links.deleteByAgent(host.id(), containerId, profile);
  }

  @Override
  public void beforeServerDeleted(String serverId) {
    disableAndUnlinkForDeletion(serverId);
  }

  /**
   * Global catalog deletion is safe and retryable: every reachable Agent copy
   * is disabled before its link is removed. A failure aborts container/catalog
   * deletion; entries already processed remain safely disabled.
   */
  public void disableAndUnlinkForDeletion(String serverId) {
    List<AgentMcpLink> linked = List.copyOf(links.findByServer(serverId));
    for (AgentMcpLink link : linked) {
      AgentProfileDto updated;
      try {
        updated = profiles.setMcpServerEnabled(
            hosts.requireConnected(link.hostId()), link.containerId(), link.profile(),
            link.alias(), false);
      } catch (NoSuchElementException staleLink) {
        // The profile/entry may have been removed outside Mission Control. No
        // live connection remains to disable, so discard only the stale link.
        links.delete(link.hostId(), link.containerId(), link.profile(), link.alias());
        continue;
      }
      if (updated.mcp().stream().noneMatch(
          server -> link.alias().equals(server.name()) && !server.enabled())) {
        throw new ResourceConflictException("could not disable MCP entry " + link.alias());
      }
      links.delete(link.hostId(), link.containerId(), link.profile(), link.alias());
    }
  }

  public AgentProfileDto enrich(DockerHostRef host, AgentProfileDto profile) {
    Map<String, AgentMcpLink> byAlias = new HashMap<>();
    for (AgentMcpLink link : links.list(host.id(), profile.containerId(), profile.name())) {
      byAlias.put(link.alias(), link);
    }
    List<AgentMcpServerDto> servers = new ArrayList<>();
    for (AgentMcpServerDto server : profile.mcp()) {
      AgentMcpLink link = byAlias.get(server.name());
      if (link == null) {
        servers.add(server);
        continue;
      }
      try {
        long currentRevision = registry.require(link.serverId()).revision();
        servers.add(server.linkedTo(link.serverId(), link.syncedRevision(), currentRevision));
      } catch (NoSuchElementException deletedCatalogEntry) {
        links.delete(host.id(), profile.containerId(), profile.name(), server.name());
        servers.add(server);
      }
    }
    return copyWithMcp(profile, servers);
  }

  private McpServerDefinition materialize(
      DockerHostRef agentHost,
      String containerId,
      McpServerDto source,
      String alias,
      boolean enabled) {
    if ("stdio".equals(source.kind())) {
      String command = source.stdioCommand();
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("catalog stdio server has no command: " + source.name());
      }
      // the catalog stores args as a list already; no shell tokenizing round trip needed
      return new McpServerDefinition(
          alias, McpServerDefinition.Transport.STDIO, null, command,
          source.args() == null ? List.of() : source.args(), enabled, null,
          registry.materializedEnvironment(source.id()));
    }
    // Joining the network is part of making the endpoint reachable, not part of working out
    // what it is — so it happens at this level, where a reader sees the Agent's networking
    // being changed. It used to sit inside the endpoint resolver below, which read as a pure
    // lookup and quietly reconfigured a container.
    joinMcpNetwork(agentHost, containerId, source);
    return new McpServerDefinition(
        alias, McpServerDefinition.Transport.of(source.transport()),
        connectionEndpoint(agentHost, source), null, List.of(),
        enabled, registry.materializedHeaders(source.id()), null);
  }

  /**
   * Attaches the Agent's container to the shared MCP network, when that is how it will reach
   * this server: a managed entry on the Agent's own host is addressed by Compose service name,
   * which resolves only from the network. Idempotent, so connecting a second server is a no-op.
   *
   * <p>Nothing to do for an external entry, a stdio one, or a managed server on another host —
   * those are reached by a URL that does not depend on this network.
   */
  private void joinMcpNetwork(
      DockerHostRef agentHost, String containerId, McpServerDto source) {
    if (!"managed".equals(source.kind()) || !agentHost.id().equals(source.hostId())) return;
    docker.connectNetwork(agentHost, containerId, ManagedMcpStack.NETWORK);
  }

  /** Where an Agent on this host reaches a network catalog entry: a managed server on its own
   *  host by Compose service name over the shared MCP network, one on another host by the
   *  address its operator published. A pure lookup — see {@link #joinMcpNetwork}. */
  private String connectionEndpoint(DockerHostRef agentHost, McpServerDto source) {
    if (!"managed".equals(source.kind())) return source.url();
    if (agentHost.id().equals(source.hostId())) {
      return registry.sameHostConnectionUrl(source.id());
    }
    String crossHost = source.crossHostUrl();
    if (crossHost == null || crossHost.isBlank()) {
      throw new IllegalArgumentException(
          "managed MCP server needs a cross-host URL for this Agent: " + source.name());
    }
    return crossHost;
  }

  private static String alias(String value) {
    String result = value == null ? "" : value.trim();
    if (!ALIAS.matcher(result).matches()) throw new IllegalArgumentException("invalid MCP alias");
    return result;
  }

  private static AgentProfileDto copyWithMcp(AgentProfileDto value, List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto(
        value.id(), value.containerId(), value.name(), value.role(), value.state(), value.provider(),
        value.model(), value.apiKeyMasked(), value.cwd(), value.soul(), value.memoryMd(),
        value.configYaml(), value.skills(), List.copyOf(mcp), value.integrations(), value.lastActive());
  }
}
