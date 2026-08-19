package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SetMcpServerEnabledRequest;
import io.hermes.missioncontrol.docker.DockerHostRef;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An agent's MCP servers.
 *
 * <p>Two kinds live side by side in one {@code config.yaml}: definitions the operator wrote
 * here, and copies linked to the global catalog. The write endpoints below assert the target
 * is custom before touching it, so a catalog-linked entry can only change by syncing.
 */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/mcp")
class AgentMcpController {

  private final HermesProfiles profiles;
  private final AgentMcpCatalogService mcpCatalog;
  private final AgentEndpoints endpoints;

  AgentMcpController(
      HermesProfiles profiles, AgentMcpCatalogService mcpCatalog, AgentEndpoints endpoints) {
    this.profiles = profiles;
    this.mcpCatalog = mcpCatalog;
    this.endpoints = endpoints;
  }

  @PostMapping
  public AgentProfileDto add(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody AddMcpServerRequest request) {
    DockerHostRef host = endpoints.host(hostId);
    mcpCatalog.assertCustom(host, containerId, name, request.name());
    return endpoints.linked(host,
        profiles.addMcpServer(host, containerId, name, McpServerDefinition.from(request)));
  }

  /** Replaces a custom MCP definition in one config write. The body name may
   * differ from {@code serverName}, in which case this is an atomic rename. */
  @PutMapping("/{serverName}")
  public AgentProfileDto update(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName,
      @Valid @RequestBody AddMcpServerRequest request) {
    DockerHostRef host = endpoints.host(hostId);
    mcpCatalog.assertCustom(host, containerId, name, serverName);
    return endpoints.linked(host,
        profiles.updateMcpServer(
            host, containerId, name, serverName, McpServerDefinition.from(request)));
  }

  /** Disconnect/reconnect is deliberately separate from permanent deletion so
   * every transport-specific setting remains available for a later reconnect. */
  @PutMapping("/{serverName}/enabled")
  public AgentProfileDto setEnabled(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName,
      @Valid @RequestBody SetMcpServerEnabledRequest request) {
    DockerHostRef host = endpoints.host(hostId);
    return endpoints.linked(host, profiles.setMcpServerEnabled(
        host, containerId, name, serverName, request.enabled()));
  }

  /** Permanently forgets the saved definition. Use the enabled endpoint for a
   * non-destructive disconnect. */
  @DeleteMapping("/{serverName}")
  public AgentProfileDto remove(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    DockerHostRef host = endpoints.host(hostId);
    AgentProfileDto updated =
        profiles.removeMcpServer(host, containerId, name, serverName);
    mcpCatalog.forgetLink(host, containerId, name, serverName);
    return endpoints.linked(host, updated);
  }

  @PostMapping("/catalog")
  public AgentProfileDto connectCatalog(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @Valid @RequestBody ConnectCatalogMcpRequest request) {
    return mcpCatalog.connect(endpoints.host(hostId), containerId, name, request);
  }

  @PostMapping("/{serverName}/sync")
  public AgentProfileDto syncCatalog(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    return mcpCatalog.sync(endpoints.host(hostId), containerId, name, serverName);
  }

  @DeleteMapping("/{serverName}/link")
  public AgentProfileDto unlinkCatalog(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    return mcpCatalog.unlink(endpoints.host(hostId), containerId, name, serverName);
  }

  @PostMapping("/{serverName}/test")
  public McpTestResult test(
      @PathVariable String hostId,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String serverName) {
    return profiles.testMcpServer(endpoints.host(hostId), containerId, name, serverName);
  }
}
