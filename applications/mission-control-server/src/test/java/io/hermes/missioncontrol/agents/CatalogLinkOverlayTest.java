package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.GatewayDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * The catalog-link overlay every profile read passes through — see
 * {@link HermesProfilesDelegationTest} for the read it is applied inside of.
 *
 * <p>These cases were {@link AgentMcpCatalogServiceTest}'s while the overlay was a public
 * method there that each controller called for itself.
 */
class CatalogLinkOverlayTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  private final AgentMcpLinkRepository links = mock(AgentMcpLinkRepository.class);
  private final McpRegistryService registry = mock(McpRegistryService.class);
  private final CatalogLinkOverlay overlay = new CatalogLinkOverlay(links, registry);

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
    when(registry.definition("mcp-1")).thenReturn(catalog);

    AgentMcpServerDto enriched = overlay.enrich(HOST, profile).mcp().getFirst();

    assertEquals("catalog", enriched.origin());
    assertEquals("mcp-1", enriched.catalogServerId());
    assertEquals(2L, enriched.syncedRevision());
    assertEquals(3L, enriched.catalogRevision());
    assertTrue(enriched.updateAvailable());
    // and it costs a row read, not a daemon round trip: this runs once per profile on every
    // /api/agents poll, and the live view forks `docker compose ps` under the host's compose
    // lock — the same lock that serializes provision/start/stop
    verify(registry, never()).live(anyString());
  }

  @Test
  void anUnlinkedEntryPassesThroughEnrichmentUntouched() {
    when(links.list("dh-local", "container", "default")).thenReturn(List.of());

    AgentMcpServerDto result = overlay.enrich(HOST, profile(List.of(custom("mine")))).mcp().getFirst();

    assertEquals("custom", result.origin());
    assertNull(result.catalogServerId());
    verifyNoInteractions(registry);
  }

  @Test
  void anEntryAtTheCurrentCatalogRevisionIsNotReportedAsUpdatable() {
    McpServerDto source = mock(McpServerDto.class);
    when(source.revision()).thenReturn(2L);
    when(links.list("dh-local", "container", "default"))
        .thenReturn(List.of(new AgentMcpLink("dh-local", "container", "default", "tools", "mcp-1", 2, 1, 1)));
    when(registry.definition("mcp-1")).thenReturn(source);

    AgentMcpServerDto result = overlay.enrich(HOST, profile(List.of(custom("tools")))).mcp().getFirst();

    assertEquals("catalog", result.origin());
    assertFalse(result.updateAvailable());
  }

  @Test
  void aLinkPointingAtADeletedCatalogEntryIsCleanedUpDuringEnrichment() {
    // the catalog row can be deleted outside this path; the Agent keeps its working definition
    // and stops being told an update is available
    when(links.list("dh-local", "container", "default"))
        .thenReturn(List.of(link("tools")));
    when(registry.definition("mcp-1")).thenThrow(new NoSuchElementException("unknown MCP server: mcp-1"));

    AgentMcpServerDto result = overlay.enrich(HOST, profile(List.of(custom("tools")))).mcp().getFirst();

    assertEquals("custom", result.origin());
    verify(links).delete("dh-local", "container", "default", "tools");
  }

  @Test
  void aLinkWhoseEntryIsNoLongerOnTheProfileIsDroppedDuringEnrichment() {
    // what a removal leaves behind if its link cleanup could not run. The page has no row left
    // to offer an unlink on, and until the row is gone assertCustom refuses the alias to
    // whatever is added under it next — so the listing has to be what clears it.
    when(links.list("dh-local", "container", "default")).thenReturn(List.of(
        new AgentMcpLink("dh-local", "container", "default", "gone", "mcp-1", 2, 1, 1)));

    AgentProfileDto result = overlay.enrich(HOST, profile(List.of(custom("tools"))));

    assertEquals(1, result.mcp().size());
    assertEquals("custom", result.mcp().getFirst().origin());
    verify(links).delete("dh-local", "container", "default", "gone");
    verifyNoInteractions(registry);
  }

  @Test
  void aProfileWhoseConfigCouldNotBeReadStrandsNothing() {
    // readFile cannot tell an unreadable config.yaml from an absent one, so a profile that
    // could not be read reports no MCP entries — indistinguishable from one whose entries were
    // all removed. Pruning on that would drop every link the agent has.
    when(links.list("dh-local", "container", "default")).thenReturn(List.of(
        new AgentMcpLink("dh-local", "container", "default", "tools", "mcp-1", 2, 1, 1)));
    AgentProfileDto unreadable = new AgentProfileDto(
        "container:default", "container", "default", "", "idle", "nous", "model", "",
        "/opt/data", "", "", "", List.of(), List.of(), List.of(), GatewayDto.unknown(), 0);

    overlay.enrich(HOST, unreadable);

    verify(links, never()).delete(anyString(), anyString(), anyString(), anyString());
  }

  private static AgentProfileDto profile(List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto(
        "container:default", "container", "default", "", "idle", "nous", "model", "",
        "/opt/data", "", "", "mcp_servers: {}\n", List.of(), mcp, List.of(), GatewayDto.unknown(), 0);
  }

  private static AgentMcpLink link(String alias) {
    return new AgentMcpLink("dh-local", "container", "default", alias, "mcp-1", 2, 1_000L, 1_000L);
  }

  private static AgentMcpServerDto custom(String name) {
    return new AgentMcpServerDto(name, name, "http", true, "unknown", 0, null, null, null,
        "http://mcp-tools:1100/mcp", null, null);
  }
}
