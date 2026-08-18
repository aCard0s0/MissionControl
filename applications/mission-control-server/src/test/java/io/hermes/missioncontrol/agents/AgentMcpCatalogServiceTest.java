package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.AgentMcpLink;
import io.hermes.missioncontrol.mcp.AgentMcpLinkRepository;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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

  // ── aliases ─────────────────────────────────────────────────────────────
  //
  // The alias becomes a key in the Agent's config.yaml and the identity of the link row, so it
  // is validated on the way in on every path that takes one.

  @Test
  void anAliasThatIsNotASafeIdentifierIsRefusedOnEveryPathThatTakesOne() {
    for (String bad : List.of("", "   ", "../etc", "-leading", "a".repeat(101), "with space")) {
      assertEquals("invalid MCP alias", assertThrows(IllegalArgumentException.class,
          () -> service.connect("dh-local", "container", "default",
              new ConnectCatalogMcpRequest("mcp-1", bad))).getMessage());
      assertThrows(IllegalArgumentException.class,
          () -> service.sync("dh-local", "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.unlink("dh-local", "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.assertCustom("dh-local", "container", "default", bad));
      assertThrows(IllegalArgumentException.class,
          () -> service.forgetLink("dh-local", "container", "default", bad));
    }
    verifyNoInteractions(profiles);
    verifyNoInteractions(links);
  }

  @Test
  void anAliasIsTrimmedBeforeItIsUsedAsAKey() {
    // ' tools ' and 'tools' must not become two entries, or a sync would edit the wrong one
    service.forgetLink("dh-local", "container", "default", "  tools  ");

    verify(links).delete("dh-local", "container", "default", "tools");
  }

  // ── connect ─────────────────────────────────────────────────────────────

  @Test
  void connectingRefusesAnAliasTheAgentAlreadyUses() {
    // the alias is a config.yaml key: connecting over it would silently replace a custom entry
    registryHas(managedCatalog("dh-local", "running"));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));

    ResourceConflictException failure = assertThrows(ResourceConflictException.class, () ->
        service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "tools")));

    assertEquals("an MCP server named 'tools' already exists on this Agent", failure.getMessage());
    verify(profiles, never()).addMcpServer(anyString(), anyString(), anyString(), any());
    verify(links, never()).upsert(any());
  }

  @Test
  void connectingRefusesAManagedCatalogServerThatIsNotRunning() {
    // its Compose service name would resolve to nothing, so the Agent would hold a dead entry
    registryHas(managedCatalog("dh-local", "exited"));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));

    assertEquals("managed MCP server is not running: Tools",
        assertThrows(ResourceConflictException.class, () ->
            service.connect("dh-local", "container", "default",
                new ConnectCatalogMcpRequest("mcp-1", "tools"))).getMessage());
    verify(docker, never()).connectNetwork(anyString(), anyString(), anyString());
  }

  @Test
  void connectingAnExternalCatalogServerCopiesItsUrlAndHeadersAndTouchesNoNetwork() {
    McpServerDto external = catalog("external", "http");
    when(external.url()).thenReturn("https://tools.example.test/mcp");
    registryHas(external);
    when(registry.materializedHeaders("mcp-1")).thenReturn(Map.of("Authorization", "Bearer secret"));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "tools"));

    AddMcpServerRequest written = capturedAdd();
    assertEquals("https://tools.example.test/mcp", written.url());
    assertEquals("Bearer secret", written.headers().get("Authorization"));
    assertTrue(written.environment().isEmpty(), "environment belongs to stdio servers only");
    verify(docker, never()).connectNetwork(anyString(), anyString(), anyString());
  }

  @Test
  void connectingAStdioCatalogServerCopiesItsCommandArgsAndMaterializedEnvironment() {
    registryHas(stdioCatalog("npx", List.of("-y", "@example/files")));
    when(registry.materializedEnvironment("mcp-1")).thenReturn(Map.of("ROOT", "/data"));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    AddMcpServerRequest written = capturedAdd();
    assertEquals("npx", written.command());
    assertEquals("-y @example/files", written.args());
    assertEquals("/data", written.environment().get("ROOT"));
    assertNull(written.url());
  }

  @Test
  void aStdioCatalogServerWithNoCommandCannotBeConnected() {
    registryHas(stdioCatalog(null, List.of()));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));

    assertEquals("catalog stdio server has no command: Tools",
        assertThrows(IllegalArgumentException.class, () ->
            service.connect("dh-local", "container", "default",
                new ConnectCatalogMcpRequest("mcp-1", "files"))).getMessage());
  }

  @Test
  void stdioArgumentsAreShellQuotedBecauseHermesRunsThemThroughAShell() {
    // the joined string is written into config.yaml and executed by the Agent's shell, so an
    // argument carrying a space or a quote must not become two arguments or close the quoting
    registryHas(stdioCatalog("npx", List.of(
        "-y", "my project", "it's", "say \"hi\"", "", "--flag=a b")));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    assertEquals("-y 'my project' 'it'\"'\"'s' 'say \"hi\"' '' '--flag=a b'", capturedAdd().args());
  }

  @Test
  void aStdioServerWithNoArgumentsWritesNoArgumentString() {
    registryHas(stdioCatalog("npx", List.of()));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    assertNull(capturedAdd().args());
  }

  // ── sync ────────────────────────────────────────────────────────────────

  @Test
  void syncingAnEntryThatIsNotLinkedIsANotFound() {
    assertEquals("MCP entry is not linked to the catalog: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.sync("dh-local", "container", "default", "tools")).getMessage());
    verify(profiles, never()).updateMcpServer(anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void syncingAnEntryTheAgentNoLongerHasIsANotFound() {
    // the operator may have deleted it from config.yaml by hand; the link is stale
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));

    assertEquals("unknown MCP server on Agent: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.sync("dh-local", "container", "default", "tools")).getMessage());
  }

  @Test
  void syncingKeepsTheEntryDisabledIfTheOperatorHadDisabledIt() {
    // a sync updates the definition, not the operator's decision to disconnect it
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default"))
        .thenReturn(profile(List.of(disabled("tools"))));
    when(profiles.updateMcpServer(anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(disabled("tools"))));

    service.sync("dh-local", "container", "default", "tools");

    ArgumentCaptor<AddMcpServerRequest> written = ArgumentCaptor.forClass(AddMcpServerRequest.class);
    verify(profiles).updateMcpServer(eq("unix:///sock"), eq("container"), eq("default"),
        eq("tools"), written.capture());
    assertEquals(Boolean.FALSE, written.getValue().enabled());
  }

  @Test
  void syncingRecordsTheCatalogRevisionItSyncedToAndKeepsTheOriginalCreationTime() {
    linkExists("tools", 2);
    registryHas(managedCatalog("dh-local", "running"));
    when(registry.sameHostConnectionUrl("mcp-1")).thenReturn("http://mcp-tools:1100/mcp");
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));
    when(profiles.updateMcpServer(anyString(), anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));

    service.sync("dh-local", "container", "default", "tools");

    ArgumentCaptor<AgentMcpLink> saved = ArgumentCaptor.forClass(AgentMcpLink.class);
    verify(links).upsert(saved.capture());
    assertEquals(7L, saved.getValue().syncedRevision(), "the link must record what it synced to");
    assertEquals(1_000L, saved.getValue().createdAt(), "createdAt belongs to the original connect");
    assertTrue(saved.getValue().updatedAt() >= 1_000L);
  }

  // ── unlink, assertCustom, forget ─────────────────────────────────────────

  @Test
  void unlinkingAnEntryThatIsNotLinkedIsANotFound() {
    assertEquals("MCP entry is not linked to the catalog: tools",
        assertThrows(NoSuchElementException.class,
            () -> service.unlink("dh-local", "container", "default", "tools")).getMessage());
    verify(links, never()).delete(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void unlinkingDropsTheLinkAndLeavesTheAgentEntryInPlaceAsCustom() {
    // the point of unlink: keep the working definition, stop tracking the catalog
    linkExists("tools", 2);
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default"))
        .thenReturn(profile(List.of(custom("tools"))));

    AgentProfileDto result = service.unlink("dh-local", "container", "default", "tools");

    verify(links).delete("dh-local", "container", "default", "tools");
    verify(profiles, never()).removeMcpServer(anyString(), anyString(), anyString(), anyString());
    assertEquals("custom", result.mcp().getFirst().origin());
  }

  @Test
  void aCatalogLinkedEntryCannotBeEditedDirectlyButACustomOneCan() {
    linkExists("tools", 2);

    assertEquals("catalog-linked MCP entries must be customized before direct editing",
        assertThrows(ResourceConflictException.class,
            () -> service.assertCustom("dh-local", "container", "default", "tools")).getMessage());

    when(links.find("dh-local", "container", "default", "mine")).thenReturn(Optional.empty());
    service.assertCustom("dh-local", "container", "default", "mine");
  }

  @Test
  void deletingAnAgentDropsAllItsLinksInOneStatement() {
    // one statement rather than one delete per alias: a partial failure used to leave a profile
    // holding some of its links
    service.deleteAgentLinks("dh-local", "container", "default");

    verify(links).deleteByAgent("dh-local", "container", "default");
    verify(links, never()).delete(anyString(), anyString(), anyString(), anyString());
  }

  // ── catalog deletion ────────────────────────────────────────────────────

  @Test
  void deletingACatalogServerDisablesEveryAgentCopyBeforeDroppingItsLink() {
    // ordering is the safety property: a link removed before the entry is disabled leaves a live
    // connection to a server that is about to disappear, with nothing recording where it came from
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("tools", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled("unix:///sock", "container", "default", "tools", false))
        .thenReturn(profile(List.of(disabled("tools"))));

    service.beforeServerDeleted("mcp-1");

    InOrder order = inOrder(profiles, links);
    order.verify(profiles).setMcpServerEnabled("unix:///sock", "container", "default", "tools", false);
    order.verify(links).delete("dh-local", "container", "default", "tools");
  }

  @Test
  void aLinkWhoseAgentOrEntryIsGoneIsDiscardedRatherThanBlockingTheDeletion() {
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("gone", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled("unix:///sock", "container", "default", "gone", false))
        .thenThrow(new NoSuchElementException("unknown MCP server on Agent: gone"));

    service.disableAndUnlinkForDeletion("mcp-1");

    verify(links).delete("dh-local", "container", "default", "gone");
  }

  @Test
  void anEntryThatCameBackStillEnabledAbortsTheDeletionAndKeepsItsLink() {
    // the write claimed to succeed but the entry is still enabled: deleting the catalog record
    // now would leave a live connection nothing can turn off
    when(links.findByServer("mcp-1")).thenReturn(List.of(link("tools", "container")));
    hostIsUp();
    when(profiles.setMcpServerEnabled("unix:///sock", "container", "default", "tools", false))
        .thenReturn(profile(List.of(custom("tools"))));

    assertEquals("could not disable MCP entry tools",
        assertThrows(IllegalStateException.class,
            () -> service.disableAndUnlinkForDeletion("mcp-1")).getMessage());
    verify(links, never()).delete(anyString(), anyString(), anyString(), anyString());
  }

  // ── enrich ──────────────────────────────────────────────────────────────

  @Test
  void anUnlinkedEntryPassesThroughEnrichmentUntouched() {
    when(links.list("dh-local", "container", "default")).thenReturn(List.of());

    AgentMcpServerDto result = service.enrich("dh-local", profile(List.of(custom("mine")))).mcp().getFirst();

    assertEquals("custom", result.origin());
    assertNull(result.catalogServerId());
    verifyNoInteractions(registry);
  }

  @Test
  void aLinkPointingAtADeletedCatalogEntryIsCleanedUpDuringEnrichment() {
    // the catalog row can be deleted outside this path; the Agent keeps its working definition
    // and stops being told an update is available
    when(links.list("dh-local", "container", "default"))
        .thenReturn(List.of(link("tools", "container")));
    when(registry.require("mcp-1")).thenThrow(new NoSuchElementException("unknown MCP server: mcp-1"));

    AgentMcpServerDto result = service.enrich("dh-local", profile(List.of(custom("tools")))).mcp().getFirst();

    assertEquals("custom", result.origin());
    verify(links).delete("dh-local", "container", "default", "tools");
  }

  @Test
  void anEntryAtTheCurrentCatalogRevisionIsNotReportedAsUpdatable() {
    McpServerDto source = mock(McpServerDto.class);
    when(source.revision()).thenReturn(2L);
    when(links.list("dh-local", "container", "default"))
        .thenReturn(List.of(new AgentMcpLink("dh-local", "container", "default", "tools", "mcp-1", 2, 1, 1)));
    when(registry.require("mcp-1")).thenReturn(source);

    AgentMcpServerDto result = service.enrich("dh-local", profile(List.of(custom("tools")))).mcp().getFirst();

    assertEquals("catalog", result.origin());
    assertFalse(result.updateAvailable());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void hostIsUp() {
    when(hosts.urlOf("dh-local")).thenReturn("unix:///sock");
  }

  private void registryHas(McpServerDto source) {
    when(registry.require("mcp-1")).thenReturn(source);
  }

  private void linkExists(String alias, long syncedRevision) {
    when(links.find("dh-local", "container", "default", alias)).thenReturn(Optional.of(
        new AgentMcpLink("dh-local", "container", "default", alias, "mcp-1", syncedRevision, 1_000L, 1_000L)));
  }

  private static AgentMcpLink link(String alias, String containerId) {
    return new AgentMcpLink("dh-local", containerId, "default", alias, "mcp-1", 2, 1_000L, 1_000L);
  }

  private static McpServerDto catalog(String kind, String transport) {
    McpServerDto source = mock(McpServerDto.class);
    when(source.id()).thenReturn("mcp-1");
    when(source.name()).thenReturn("Tools");
    when(source.kind()).thenReturn(kind);
    when(source.transport()).thenReturn(transport);
    when(source.revision()).thenReturn(7L);
    return source;
  }

  private static McpServerDto managedCatalog(String hostId, String runtimeState) {
    McpServerDto source = catalog("managed", "http");
    when(source.hostId()).thenReturn(hostId);
    when(source.runtimeState()).thenReturn(runtimeState);
    return source;
  }

  private static McpServerDto stdioCatalog(String command, List<String> args) {
    McpServerDto source = catalog("stdio", "stdio");
    when(source.stdioCommand()).thenReturn(command);
    when(source.args()).thenReturn(args);
    return source;
  }

  private AddMcpServerRequest capturedAdd() {
    ArgumentCaptor<AddMcpServerRequest> request = ArgumentCaptor.forClass(AddMcpServerRequest.class);
    verify(profiles).addMcpServer(anyString(), anyString(), anyString(), request.capture());
    return request.getValue();
  }

  private static AgentMcpServerDto custom(String name) {
    return new AgentMcpServerDto(name, name, "http", true, "unknown", 0, null, null, null,
        "http://mcp-tools:1100/mcp", null, null);
  }

  private static AgentMcpServerDto disabled(String name) {
    return new AgentMcpServerDto(name, name, "http", false, "unknown", 0, null, null, null,
        "http://mcp-tools:1100/mcp", null, null);
  }

  @Test
  void connectingAManagedCatalogServerFromAnotherHostUsesItsCrossHostUrl() {
    // the MCP network is local to each daemon, so the service name resolves nowhere from here
    McpServerDto source = managedCatalog("dh-remote", "running");
    when(source.crossHostUrl()).thenReturn("https://peer.test/mcp");
    registryHas(source);
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("tools"))));
    enrichesTransparently();

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "tools"));

    assertEquals("https://peer.test/mcp", capturedAdd().url());
    verify(docker, never()).connectNetwork(anyString(), anyString(), anyString());
  }

  @Test
  void aStdioCatalogServerNeedsNoNetworkAndNoUrl() {
    registryHas(stdioCatalog("npx", List.of("a b")));
    hostIsUp();
    when(profiles.get("unix:///sock", "container", "default")).thenReturn(profile(List.of()));
    when(profiles.addMcpServer(anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(List.of(custom("files"))));
    enrichesTransparently();

    service.connect("dh-local", "container", "default", new ConnectCatalogMcpRequest("mcp-1", "files"));

    assertNull(capturedAdd().url());
    // one argument carrying a space must not become two
    assertEquals("'a b'", capturedAdd().args());
    verify(docker, never()).connectNetwork(anyString(), anyString(), anyString());
  }

  private void enrichesTransparently() {
    when(links.list(anyString(), anyString(), anyString())).thenReturn(List.of());
  }
}
