package io.hermes.missioncontrol.agents.web;

import static io.hermes.missioncontrol.agents.web.AgentWebFixture.BASE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.CONTAINER;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.HOST;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.PROFILE;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.enrichmentIsTransparent;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsConnected;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.hostIsDown;
import static io.hermes.missioncontrol.agents.web.AgentWebFixture.profile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.agents.AgentLifecycle;
import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * An agent's MCP endpoints. The rule that carries the weight here is that a catalog-linked
 * entry may only change by syncing, so the write paths assert the target is custom first —
 * and that every one of them refuses a disconnected host before anything is written.
 *
 * <p>What a removal then does on both sides of the container/SQLite split is
 * {@code AgentLifecycleTest}'s.
 */
class AgentMcpControllerTest {

  private static final String MCP = BASE + "/mcp";
  private static final String SERVER = "files";
  private static final String BODY = """
      {"name":"files","transport":"stdio","command":"npx","args":"-y @example/files",
       "enabled":true,"environment":{"ROOT":"/data"}}
      """;

  private HermesProfiles profiles;
  private HostService hosts;
  private AgentMcpCatalogService mcpCatalog;
  private AgentLifecycle lifecycle;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    profiles = mock(HermesProfiles.class);
    hosts = mock(HostService.class);
    mcpCatalog = mock(AgentMcpCatalogService.class);
    lifecycle = mock(AgentLifecycle.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new AgentMcpController(
            profiles, mcpCatalog, lifecycle, new AgentEndpoints(hosts, mcpCatalog)))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void addingAServerAssertsItIsCustomBeforeTheProfileIsWritten() throws Exception {
    hostIsConnected(hosts);
    enrichmentIsTransparent(mcpCatalog);
    when(profiles.addMcpServer(any(), anyString(), anyString(), any())).thenReturn(profile(PROFILE));

    mvc.perform(post(MCP).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(PROFILE));

    InOrder order = inOrder(mcpCatalog, profiles);
    order.verify(mcpCatalog).assertCustom(HOST, CONTAINER, PROFILE, SERVER);
    order.verify(profiles).addMcpServer(eq(HOST), eq(CONTAINER), eq(PROFILE), any(McpServerDefinition.class));
  }

  @Test
  void aCatalogLinkedServerCannotBeOverwrittenThroughTheCustomWritePath() throws Exception {
    hostIsConnected(hosts);
    doThrow(new IllegalArgumentException("this server is linked to the catalog; sync it instead"))
        .when(mcpCatalog).assertCustom(HOST, CONTAINER, PROFILE, SERVER);

    mvc.perform(post(MCP).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("this server is linked to the catalog; sync it instead"));

    verify(profiles, never()).addMcpServer(any(), anyString(), anyString(), any());
  }

  @Test
  void addingAServerRequiresANameAndATransport() throws Exception {
    mvc.perform(post(MCP).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\",\"transport\":\"\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(profiles);
    verifyNoInteractions(lifecycle);
    verifyNoInteractions(hosts);
  }

  @Test
  void updatingChecksThePathNameNotTheBodyNameSoARenameStaysAtomic() throws Exception {
    hostIsConnected(hosts);
    enrichmentIsTransparent(mcpCatalog);
    when(profiles.updateMcpServer(any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(profile(PROFILE));

    mvc.perform(put(MCP + "/" + SERVER).contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"files-v2","transport":"stdio","command":"npx","args":"-y @example/files"}
            """))
        .andExpect(status().isOk());

    // the entry being replaced is the one named in the path; the body name is its new name
    verify(mcpCatalog).assertCustom(HOST, CONTAINER, PROFILE, SERVER);
    verify(profiles).updateMcpServer(eq(HOST), eq(CONTAINER), eq(PROFILE), eq(SERVER), any());
  }

  @Test
  void togglingAServerRequiresAnExplicitBooleanSoAMissingFieldIsNotReadAsFalse() throws Exception {
    mvc.perform(put(MCP + "/" + SERVER + "/enabled")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(profiles);
    verifyNoInteractions(lifecycle);
  }

  @Test
  void togglingAServerWritesTheFlag() throws Exception {
    hostIsConnected(hosts);
    enrichmentIsTransparent(mcpCatalog);
    when(profiles.setMcpServerEnabled(HOST, CONTAINER, PROFILE, SERVER, false)).thenReturn(profile(PROFILE));

    mvc.perform(put(MCP + "/" + SERVER + "/enabled")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
        .andExpect(status().isOk());

    verify(profiles).setMcpServerEnabled(HOST, CONTAINER, PROFILE, SERVER, false);
  }

  @Test
  void removingAServerHandsTheResolvedHostToTheLifecycle() throws Exception {
    // the profile write and the link drop, and the order between them, are AgentLifecycleTest's
    hostIsConnected(hosts);
    enrichmentIsTransparent(mcpCatalog);
    when(lifecycle.removeMcpServer(HOST, CONTAINER, PROFILE, SERVER)).thenReturn(profile(PROFILE));

    mvc.perform(delete(MCP + "/" + SERVER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value(PROFILE));

    verify(lifecycle).removeMcpServer(HOST, CONTAINER, PROFILE, SERVER);
  }

  @Test
  void connectingToTheCatalogRequiresBothAServerIdAndAnAlias() throws Exception {
    mvc.perform(post(MCP + "/catalog")
            .contentType(MediaType.APPLICATION_JSON).content("{\"serverId\":\"srv-1\",\"alias\":\"\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(hosts);
  }

  @Test
  void theCatalogEndpointsCheckTheHostThenDelegateToTheCatalogService() throws Exception {
    hostIsConnected(hosts);
    enrichmentIsTransparent(mcpCatalog);
    when(mcpCatalog.connect(eq(HOST), eq(CONTAINER), eq(PROFILE), any(ConnectCatalogMcpRequest.class)))
        .thenReturn(profile(PROFILE));
    when(mcpCatalog.sync(HOST, CONTAINER, PROFILE, SERVER)).thenReturn(profile(PROFILE));
    when(mcpCatalog.unlink(HOST, CONTAINER, PROFILE, SERVER)).thenReturn(profile(PROFILE));

    mvc.perform(post(MCP + "/catalog").contentType(MediaType.APPLICATION_JSON)
            .content("{\"serverId\":\"srv-1\",\"alias\":\"files\"}"))
        .andExpect(status().isOk());
    mvc.perform(post(MCP + "/" + SERVER + "/sync")).andExpect(status().isOk());
    mvc.perform(delete(MCP + "/" + SERVER + "/link")).andExpect(status().isOk());

    verify(mcpCatalog).sync(HOST, CONTAINER, PROFILE, SERVER);
    verify(mcpCatalog).unlink(HOST, CONTAINER, PROFILE, SERVER);
    // the controller resolves the host once and hands the ref down, so nothing downstream
    // has a reason to resolve it a second time
    verify(hosts, never()).ref(anyString());
  }

  @Test
  void testingAServerReportsTheProbeResult() throws Exception {
    hostIsConnected(hosts);
    when(profiles.testMcpServer(HOST, CONTAINER, PROFILE, SERVER))
        .thenReturn(new McpTestResult(SERVER, "ok", 7, 42L, null, 1_700_000_000_000L));

    mvc.perform(post(MCP + "/" + SERVER + "/test"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.tools").value(7));
  }

  @Test
  void aDisconnectedHostFailsEveryMcpEndpointBeforeAnythingIsWritten() throws Exception {
    hostIsDown(hosts);

    for (RequestBuilder request : List.of(
        post(MCP).contentType(MediaType.APPLICATION_JSON).content(BODY),
        put(MCP + "/" + SERVER).contentType(MediaType.APPLICATION_JSON).content(BODY),
        put(MCP + "/" + SERVER + "/enabled")
            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"),
        delete(MCP + "/" + SERVER),
        post(MCP + "/catalog").contentType(MediaType.APPLICATION_JSON)
            .content("{\"serverId\":\"srv-1\",\"alias\":\"files\"}"),
        post(MCP + "/" + SERVER + "/sync"),
        delete(MCP + "/" + SERVER + "/link"),
        post(MCP + "/" + SERVER + "/test"))) {
      mvc.perform(request).andExpect(status().isServiceUnavailable());
    }

    verifyNoInteractions(profiles);
    verifyNoInteractions(lifecycle);
    verify(mcpCatalog, never()).connect(any(), anyString(), anyString(), any());
    verify(mcpCatalog, never()).sync(any(), anyString(), anyString(), anyString());
    verify(mcpCatalog, never()).unlink(any(), anyString(), anyString(), anyString());
  }
}
