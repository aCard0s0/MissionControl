package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.agents.api.AgentTargetRequest;
import io.hermes.missioncontrol.common.IdList;
import io.hermes.missioncontrol.common.Text;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpGroupDto.McpGroupAgentDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP groups: a named set of catalog entries, and one button that connects the whole set to
 * one agent.
 *
 * <p>The deploy itself is {@link McpGroupDeploy}'s: several independent writes to an agent
 * someone else owns, failing one at a time, which is more than an HTTP handler should be
 * holding. What is left here is what a controller is for — resolve the group, resolve the host,
 * hand both over.
 *
 * <p>Which agents a group reaches is read back from {@code mcp_agent_links} on every list and
 * stored nowhere; {@link McpGroup} says why at length.
 */
@RestController
@RequestMapping("/api/mcp-groups")
public class McpGroupController {

  private static final int MAX_SERVERS = 64;

  public record UpsertMcpGroupRequest(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 2_000) String description,
      @Size(max = MAX_SERVERS) List<@Size(max = 64) String> serverIds) {
  }

  private final McpGroupRepository repository;
  private final AgentMcpLinkRepository links;
  private final HostService hosts;
  private final McpGroupDeploy deploy;

  public McpGroupController(
      McpGroupRepository repository, AgentMcpLinkRepository links, HostService hosts,
      McpGroupDeploy deploy) {
    this.repository = repository;
    this.links = links;
    this.hosts = hosts;
    this.deploy = deploy;
  }

  @GetMapping
  public List<McpGroupDto> list() {
    return repository.findAll().stream().map(this::withAgents).toList();
  }

  @PostMapping
  public McpGroupDto create(@Valid @RequestBody UpsertMcpGroupRequest request) {
    long now = System.currentTimeMillis();
    McpGroup group = normalize(
        "mg-" + UUID.randomUUID().toString().substring(0, 8), request, now, now);
    repository.insert(group);
    return withAgents(group);
  }

  @PutMapping("/{id}")
  public McpGroupDto update(
      @PathVariable String id, @Valid @RequestBody UpsertMcpGroupRequest request) {
    McpGroup existing = repository.find(id).orElseThrow(() -> unknown(id));
    McpGroup updated = normalize(
        existing.id(), request, existing.createdAt(), System.currentTimeMillis());
    repository.update(updated);
    return withAgents(updated);
  }

  /**
   * Idempotent, and reaches no agent.
   *
   * <p>Deleting a group leaves every connection it ever made in place: the servers stay in the
   * catalog and the agents stay connected to them. Only the set goes. Disconnecting is the
   * agent's own MCP tab, one alias at a time and deliberately — the same rule the link itself
   * follows, where disabling an entry keeps the row.
   */
  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    repository.delete(id);
  }

  /** Resolves the group and the host, and hands both to {@link McpGroupDeploy}. */
  @PostMapping("/{id}/deploy")
  public McpGroupDeploy.Deployed deploy(
      @PathVariable String id, @Valid @RequestBody AgentTargetRequest request) {
    McpGroup group = repository.find(id).orElseThrow(() -> unknown(id));
    return deploy.onto(group, hosts.requireConnected(request.hostId()),
        request.containerId(), request.profile());
  }

  /**
   * The group, plus every agent connected to any of its servers.
   *
   * <p>One pass over each server's links rather than a query per agent: the set of agents is
   * not known until the links are read, and there are only ever a handful of servers in a
   * group.
   */
  private McpGroupDto withAgents(McpGroup group) {
    // keyed by the three columns that address a profile, which is what a link is keyed by too
    Map<String, McpGroupAgentDto> byAgent = new LinkedHashMap<>();
    for (String serverId : group.serverIds()) {
      for (AgentMcpLink link : links.findByServer(serverId)) {
        String key = link.hostId() + " " + link.containerId() + " " + link.profile();
        McpGroupAgentDto seen = byAgent.get(key);
        byAgent.put(key, new McpGroupAgentDto(
            link.hostId(), link.containerId(), link.profile(),
            seen == null ? 1 : seen.linked() + 1));
      }
    }
    List<McpGroupAgentDto> agents = new ArrayList<>(byAgent.values());
    // most complete first: the agent that has the whole group is the one to read first
    agents.sort(Comparator.comparingInt(McpGroupAgentDto::linked).reversed()
        .thenComparing(McpGroupAgentDto::profile));
    return new McpGroupDto(group.id(), group.name(), group.description(), group.serverIds(),
        List.copyOf(agents), group.createdAt(), group.updatedAt());
  }

  private static McpGroup normalize(
      String id, UpsertMcpGroupRequest request, long createdAt, long now) {
    return new McpGroup(
        id, request.name().trim(), Text.blankToNull(request.description()),
        IdList.normalize(request.serverIds()), createdAt, now);
  }

  private static NoSuchElementException unknown(String id) {
    return new NoSuchElementException("unknown MCP group: " + id);
  }
}
