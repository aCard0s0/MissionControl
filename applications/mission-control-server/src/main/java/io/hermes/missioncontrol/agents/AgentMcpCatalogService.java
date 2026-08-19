package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
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

  private static final String MCP_NETWORK = "mission-control-mcp-net";
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
    AddMcpServerRequest definition = materialize(host, containerId, source, alias, true);
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
    AddMcpServerRequest definition = materialize(
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

  private AddMcpServerRequest materialize(
      DockerHostRef agentHost,
      String containerId,
      McpServerDto source,
      String alias,
      boolean enabled) {
    String endpoint = null;
    String command = null;
    String args = null;
    Map<String, String> headers = Map.of();
    Map<String, String> environment = Map.of();

    if ("stdio".equals(source.kind())) {
      command = source.stdioCommand();
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("catalog stdio server has no command: " + source.name());
      }
      args = joinArgs(source.args());
      environment = registry.materializedEnvironment(source.id());
    } else {
      if ("managed".equals(source.kind()) && agentHost.id().equals(source.hostId())) {
        docker.connectNetwork(agentHost, containerId, MCP_NETWORK);
        endpoint = registry.sameHostConnectionUrl(source.id());
      } else if ("managed".equals(source.kind())) {
        endpoint = source.crossHostUrl();
        if (endpoint == null || endpoint.isBlank()) {
          throw new IllegalArgumentException(
              "managed MCP server needs a cross-host URL for this Agent: " + source.name());
        }
      } else {
        endpoint = source.url();
      }
      headers = registry.materializedHeaders(source.id());
    }
    return new AddMcpServerRequest(
        alias, source.transport(), endpoint, command, args, enabled, headers, environment);
  }

  private static String joinArgs(List<String> values) {
    if (values == null || values.isEmpty()) return null;
    return values.stream().map(AgentMcpCatalogService::quoteArg)
        .reduce((left, right) -> left + " " + right).orElse(null);
  }

  private static String quoteArg(String value) {
    if (value == null) return "''";
    if (!value.isEmpty() && value.chars().noneMatch(
        ch -> Character.isWhitespace(ch) || ch == '\'' || ch == '"')) return value;
    return "'" + value.replace("'", "'\"'\"'") + "'";
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
