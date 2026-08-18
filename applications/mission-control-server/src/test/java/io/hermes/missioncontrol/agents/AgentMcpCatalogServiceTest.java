package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentMcpCatalogServiceTest {

  private final McpRegistryService registry = mock(McpRegistryService.class);
  private final AgentMcpLinkRepository links = mock(AgentMcpLinkRepository.class);
  private final HostService hosts = mock(HostService.class);
  private final DockerGateway docker = mock(DockerGateway.class);
  private final HermesProfiles profiles = mock(HermesProfiles.class);
  private final AgentMcpCatalogService service =
      new AgentMcpCatalogService(registry, links, hosts, docker, profiles);

  @Test
  void enrichesMaterializedAgentEntryWithCatalogRevisionMetadata() {
    AgentMcpServerDto local = new AgentMcpServerDto(
        "tools", "tools", "http", true, "connected", 4, 12L, null, 100L,
        "http://tools:1100/mcp", null, null);
    AgentProfileDto profile = profile(List.of(local));
    AgentMcpLink link = new AgentMcpLink(
        "dh-local", "container", "default", "tools", "mcp-1", 2, 1, 1);
    var catalog = mock(McpServerDto.class);
    when(catalog.revision()).thenReturn(3L);
    when(links.list("dh-local", "container", "default")).thenReturn(List.of(link));
    when(registry.require("mcp-1")).thenReturn(catalog);

    AgentMcpServerDto enriched = service.enrich("dh-local", profile).mcp().getFirst();

    assertEquals("catalog", enriched.origin());
    assertEquals("mcp-1", enriched.catalogServerId());
    assertEquals(2L, enriched.syncedRevision());
    assertEquals(3L, enriched.catalogRevision());
    assertTrue(enriched.updateAvailable());
  }

  @Test
  void sameHostConnectAttachesNetworkAndMaterializesSecretsServerSide() {
    var catalog = mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Tools");
    when(catalog.kind()).thenReturn("managed");
    when(catalog.hostId()).thenReturn("dh-local");
    when(catalog.runtimeState()).thenReturn("running");
    when(catalog.transport()).thenReturn("http");
    when(catalog.revision()).thenReturn(7L);
    when(registry.require("mcp-1")).thenReturn(catalog);
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    when(registry.materializedHeaders("mcp-1"))
        .thenReturn(Map.of("Authorization", "Bearer secret"));
    when(links.list("dh-local", "container", "default")).thenReturn(List.of(
        new AgentMcpLink("dh-local", "container", "default", "tools", "mcp-1", 7, 1, 1)));
    when(hosts.urlOf("dh-local")).thenReturn("unix:///sock");
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(
        org.mockito.ArgumentMatchers.eq("unix:///sock"),
        org.mockito.ArgumentMatchers.eq("container"),
        org.mockito.ArgumentMatchers.eq("default"),
        org.mockito.ArgumentMatchers.any(AddMcpServerRequest.class)))
        .thenReturn(profile(List.of(new AgentMcpServerDto(
            "tools", "tools", "http", true, "unknown", 0, null, null, null,
            "http://mcp-tools:1100/mcp", null, null))));

    AgentProfileDto result = service.connect(
        "dh-local", "container", "default",
        new ConnectCatalogMcpRequest("mcp-1", "tools"));

    verify(docker).connectNetwork("unix:///sock", "container", "mission-control-mcp-net");
    ArgumentCaptor<AddMcpServerRequest> request = ArgumentCaptor.forClass(AddMcpServerRequest.class);
    verify(profiles).addMcpServer(
        org.mockito.ArgumentMatchers.eq("unix:///sock"),
        org.mockito.ArgumentMatchers.eq("container"),
        org.mockito.ArgumentMatchers.eq("default"), request.capture());
    assertEquals("http://mcp-tools:1100/mcp", request.getValue().url());
    assertEquals("Bearer secret", request.getValue().headers().get("Authorization"));
    assertEquals("catalog", result.mcp().getFirst().origin());
    verify(links).upsert(org.mockito.ArgumentMatchers.argThat(
        value -> "mcp-1".equals(value.serverId()) && value.syncedRevision() == 7));
  }

  @Test
  void crossHostManagedConnectRequiresExplicitUrl() {
    var catalog = mock(McpServerDto.class);
    when(catalog.id()).thenReturn("mcp-1");
    when(catalog.name()).thenReturn("Tools");
    when(catalog.kind()).thenReturn("managed");
    when(catalog.hostId()).thenReturn("dh-remote");
    when(catalog.runtimeState()).thenReturn("running");
    when(catalog.transport()).thenReturn("http");
    when(registry.require("mcp-1")).thenReturn(catalog);
    when(hosts.urlOf("dh-local")).thenReturn("unix:///sock");
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
        service.connect("dh-local", "container", "default",
            new ConnectCatalogMcpRequest("mcp-1", "tools")));

    assertTrue(error.getMessage().contains("cross-host URL"));
  }

  private static AgentProfileDto profile(List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto(
        "container:default", "container", "default", "", "idle", "nous", "model", "",
        "/opt/data", "", "", "mcp_servers: {}\n", List.of(), mcp, List.of(), 0);
  }
}
