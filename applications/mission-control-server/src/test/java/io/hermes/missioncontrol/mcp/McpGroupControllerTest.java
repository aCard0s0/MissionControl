package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.AgentMcpCatalogService;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.ConnectCatalogMcpRequest;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * MCP groups: the set, the deploy that connects it, and the agent coverage read back from the
 * links.
 *
 * <p>Two things here are the whole point of the design and are pinned as such. The agents a
 * group reaches are **derived** from {@code mcp_agent_links}, never stored — so a half
 * disconnected agent reports a partial count rather than looking connected. And an alias the
 * agent already holds is **skipped, not failed**, because topping up a partly-connected agent
 * is the ordinary use of the button.
 */
class McpGroupControllerTest {

  private static final DockerHostRef HOST =
      new DockerHostRef("dh-local", "unix:///var/run/docker.sock");

  private SqliteTestDatabase database;
  private McpGroupRepository repository;
  private AgentMcpLinkRepository links;
  private McpRegistryService registry;
  private AgentMcpCatalogService mcpCatalog;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new McpGroupRepository(database.jdbc(), new ObjectMapper());
    links = new AgentMcpLinkRepository(database.jdbc());
    registry = mock(McpRegistryService.class);
    mcpCatalog = mock(AgentMcpCatalogService.class);
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any()))
        .thenReturn(Optional.of(mock(AgentProfileDto.class)));
    HostService hosts = mock(HostService.class);
    when(hosts.requireConnected(anyString())).thenReturn(HOST);
    mvc = MockMvcBuilders
        .standaloneSetup(new McpGroupController(repository, links, hosts, new McpGroupDeploy(registry, mcpCatalog)))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static McpServerDto server(String id, String name) {
    return new McpServerDto(id, name, "desc", null, "external", "dh-local", "svc", "http",
        "http://files:8080", "http://files:8080", "img:1", null, List.of(), List.of(), null,
        List.of(), 8080, null, "/mcp", null, List.of(), List.of(), List.of(), null, List.of(),
        "running", "running", null, null, 1L, 1L, false, "ok", null, 5L, 3L, 1L, 2L);
  }

  /** The catalog entry the group names exists and is called {@code name}. */
  private void known(String id, String name) {
    when(registry.definition(id)).thenReturn(server(id, name));
  }

  private void link(String serverId, String profile, String alias) {
    links.upsert(new AgentMcpLink("dh-local", "c-1", profile, alias, serverId, 1L, 1_000L, 1_000L));
  }

  private static final String TARGET =
      "{\"hostId\":\"dh-local\",\"containerId\":\"c-1\",\"profile\":\"atlas\"}";

  // --- the set ----------------------------------------------------------------------

  @Test
  void createsAGroup() throws Exception {
    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\",\"serverIds\":[\"m-1\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("research"))
        .andExpect(jsonPath("$.serverIds[0]").value("m-1"))
        .andExpect(jsonPath("$.agents.length()").value(0));
  }

  @Test
  void dropsBlankDuplicateAndNullServerIdsAndKeepsTheOrder() throws Exception {
    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\",\"serverIds\":[\"m-2\",\"\",null,\"m-1\",\"m-2\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverIds.length()").value(2))
        .andExpect(jsonPath("$.serverIds[0]").value("m-2"))
        .andExpect(jsonPath("$.serverIds[1]").value("m-1"));
  }

  @Test
  void treatsABlankDescriptionAsAbsent() throws Exception {
    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\",\"description\":\"  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.description").value((Object) null));
  }

  @Test
  void rejectsAGroupWithNoName() throws Exception {
    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  \"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void answersConflictWhenTwoGroupsWouldReadTheSame() throws Exception {
    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\"}"))
        .andExpect(status().isOk());

    mvc.perform(post("/api/mcp-groups").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"RESEARCH\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void anUpdateReplacesTheMembershipAndKeepsCreatedAt() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", "old", List.of("m-1"), 1_000L, 1_000L));

    mvc.perform(put("/api/mcp-groups/mg-1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\",\"serverIds\":[\"m-9\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serverIds[0]").value("m-9"));

    assertEquals(1_000L, repository.find("mg-1").orElseThrow().createdAt());
  }

  @Test
  void answersNotFoundRatherThanInsertingWhenTheGroupIsGone() throws Exception {
    mvc.perform(put("/api/mcp-groups/mg-nope").contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"research\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletingAGroupLeavesEveryConnectionItEverMade() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1"), 1_000L, 1_000L));
    link("m-1", "atlas", "files");

    mvc.perform(delete("/api/mcp-groups/mg-1")).andExpect(status().isOk());

    assertEquals(1, links.list("dh-local", "c-1", "atlas").size());
    // idempotent, like every other delete in this application
    mvc.perform(delete("/api/mcp-groups/mg-1")).andExpect(status().isOk());
  }

  @Test
  void listsGroupsByName() throws Exception {
    repository.insert(new McpGroup("mg-1", "zebra", null, List.of(), 1_000L, 9_000L));
    repository.insert(new McpGroup("mg-2", "alpha", null, List.of(), 1_000L, 1_000L));

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("alpha"))
        .andExpect(jsonPath("$[1].name").value("zebra"));
  }

  // --- the agents, derived ----------------------------------------------------------

  @Test
  void readsTheAgentsAGroupReachesOffTheLinksRatherThanStoringThem() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1", "m-2"),
        1_000L, 1_000L));
    link("m-1", "atlas", "files");
    link("m-2", "atlas", "search");

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agents.length()").value(1))
        .andExpect(jsonPath("$[0].agents[0].profile").value("atlas"))
        .andExpect(jsonPath("$[0].agents[0].linked").value(2));
  }

  @Test
  void reportsAPartialCountForAnAgentThatHasSomeOfTheGroup() throws Exception {
    // the reason this is derived and not stored: no association could have told you this
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1", "m-2"),
        1_000L, 1_000L));
    link("m-1", "atlas", "files");

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agents[0].linked").value(1));
  }

  @Test
  void oneGroupCanReachSeveralAgentsAtOnce() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1"), 1_000L, 1_000L));
    link("m-1", "atlas", "files");
    link("m-1", "borealis", "files");

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agents.length()").value(2));
  }

  @Test
  void oneServerInTwoGroupsCountsTowardBoth() throws Exception {
    repository.insert(new McpGroup("mg-1", "alpha", null, List.of("m-1"), 1_000L, 1_000L));
    repository.insert(new McpGroup("mg-2", "beta", null, List.of("m-1"), 1_000L, 1_000L));
    link("m-1", "atlas", "files");

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agents[0].profile").value("atlas"))
        .andExpect(jsonPath("$[1].agents[0].profile").value("atlas"));
  }

  @Test
  void ordersTheAgentsMostCompleteFirst() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1", "m-2"),
        1_000L, 1_000L));
    link("m-1", "atlas", "files");
    link("m-1", "borealis", "files");
    link("m-2", "borealis", "search");

    mvc.perform(get("/api/mcp-groups"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].agents[0].profile").value("borealis"))
        .andExpect(jsonPath("$[0].agents[0].linked").value(2))
        .andExpect(jsonPath("$[0].agents[1].profile").value("atlas"));
  }

  // --- the deploy -------------------------------------------------------------------

  @Test
  void connectsEveryServerInTheGroupToOneAgent() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1", "m-2"),
        1_000L, 1_000L));
    known("m-1", "files");
    known("m-2", "search");

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts.length()").value(2))
        .andExpect(jsonPath("$.parts[0].status").value("deployed"))
        .andExpect(jsonPath("$.parts[1].status").value("deployed"));

    verify(mcpCatalog).connectIfAbsent(eq(HOST), eq("c-1"), eq("atlas"),
        eq(new ConnectCatalogMcpRequest("m-1", "files")));
    verify(mcpCatalog).connectIfAbsent(eq(HOST), eq("c-1"), eq("atlas"),
        eq(new ConnectCatalogMcpRequest("m-2", "search")));
  }

  @Test
  void skipsAServerTheCatalogNoLongerHasRatherThanFailingTheWholeDeploy() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("gone", "m-2"),
        1_000L, 1_000L));
    when(registry.definition("gone")).thenThrow(new NoSuchElementException("unknown"));
    known("m-2", "search");

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("skipped"))
        .andExpect(jsonPath("$.parts[0].detail").value("no longer in the catalog"))
        // the rest of the group still goes over
        .andExpect(jsonPath("$.parts[1].status").value("deployed"));
  }

  @Test
  void anAliasTheAgentAlreadyHasReadsAsSkippedRatherThanFailed() throws Exception {
    // topping up an agent that has part of the group is the ordinary use of this button. The
    // catalog service reports the case rather than throwing prose for this handler to match —
    // matching it is how a guide's deploy came to call the same event a failure.
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1"), 1_000L, 1_000L));
    known("m-1", "files");
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any()))
        .thenReturn(Optional.empty());

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("skipped"))
        .andExpect(jsonPath("$.parts[0].detail").value("already connected"));
  }

  @Test
  void reportsARealRefusalAsFailedWithItsReason() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1"), 1_000L, 1_000L));
    known("m-1", "files");
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any())).thenThrow(
        new ResourceConflictException("managed MCP server is not running: files"));

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("failed"))
        .andExpect(jsonPath("$.parts[0].detail").value("managed MCP server is not running: files"));
  }

  @Test
  void answersNotFoundWhenTheGroupBeingDeployedIsGone() throws Exception {
    mvc.perform(post("/api/mcp-groups/mg-nope/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectsADeployWithAProfileNameAShellWouldNotTake() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of("m-1"), 1_000L, 1_000L));

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content("{\"hostId\":\"dh-local\",\"containerId\":\"c-1\",\"profile\":\"a; rm -rf /\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anEmptyGroupDeploysNothingAndSaysSo() throws Exception {
    repository.insert(new McpGroup("mg-1", "research", null, List.of(), 1_000L, 1_000L));

    mvc.perform(post("/api/mcp-groups/mg-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(TARGET))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts.length()").value(0));
  }
}
