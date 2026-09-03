package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Guides, and the deploy that is several independent writes to someone else's agent.
 *
 * <p>Partial failure is the subject. A guide names things that outlive it badly — a skill
 * deleted from the library, an MCP server removed from the catalog, an alias already taken
 * on that agent — and none of those should cost the operator the parts that would have
 * worked. What the response says about each part is the whole contract.
 */
class SkillGuideControllerTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-1", "unix:///var/run/docker.sock");

  private SqliteTestDatabase database;
  private SkillGuideRepository repository;
  private SkillRepository skills;
  private HermesProfiles profiles;
  private McpRegistryService registry;
  private AgentMcpCatalogService mcpCatalog;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillGuideRepository(database.jdbc(), new ObjectMapper());
    skills = new SkillRepository(database.jdbc(), new ObjectMapper());
    profiles = mock(HermesProfiles.class);
    when(profiles.get(any(), anyString(), anyString())).thenReturn(mock(AgentProfileDto.class));
    registry = mock(McpRegistryService.class);
    mcpCatalog = mock(AgentMcpCatalogService.class);
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any()))
        .thenReturn(Optional.of(mock(AgentProfileDto.class)));
    HostService hosts = mock(HostService.class);
    when(hosts.requireConnected(anyString())).thenReturn(HOST);
    mvc = MockMvcBuilders
        .standaloneSetup(new SkillGuideController(
            repository, skills, new SkillDeployer(profiles), registry, mcpCatalog, profiles, hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static SkillGuide guide(List<String> skillIds, List<String> mcpIds) {
    return new SkillGuide("g-1", "pdf-workflow", "triage a broken export",
        "Read the log, then the PDF.", "docs", skillIds, mcpIds, 1_000L, 2_000L);
  }

  private void storeSkill(String id, String name) {
    skills.insert(new Skill(id, Skill.LOCAL, name, null, "docs", null, null,
        List.of(new SkillFile("SKILL.md", "# " + name)), 1_000L, 1_000L));
  }

  private void catalogServer(String id, String name) {
    McpServerDto dto = mock(McpServerDto.class);
    when(dto.name()).thenReturn(name);
    when(registry.definition(id)).thenReturn(dto);
  }

  private static String target() {
    return """
        {"hostId":"dh-1","containerId":"c1","profile":"ops"}
        """;
  }

  /** The umbrella SKILL.md the deploy wrote, or null when it never got that far. */
  private String umbrella() {
    ArgumentCaptor<Map<String, String>> files = ArgumentCaptor.forClass(Map.class);
    verify(profiles).installSkillFiles(any(), anyString(), anyString(), eq("pdf-workflow"),
        files.capture());
    return files.getValue().get(Skill.SKILL_MD);
  }

  // ── crud ───────────────────────────────────────────────────────────────────

  @Test
  void aCreatedGuideIsPersistedAndComesBackFromTheListEndpoint() throws Exception {
    mvc.perform(post("/api/skill-guides").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"pdf-workflow","description":"triage","body":"do this","category":"Docs",
             "skillIds":["s-1","s-1","","s-2"],"mcpServerIds":["m-1"]}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("docs"))
        // blanks and duplicates dropped, order kept — it is the order the agent reads
        .andExpect(jsonPath("$.skillIds").isArray())
        .andExpect(jsonPath("$.skillIds[0]").value("s-1"))
        .andExpect(jsonPath("$.skillIds[1]").value("s-2"))
        .andExpect(jsonPath("$.skillIds.length()").value(2));

    mvc.perform(get("/api/skill-guides")).andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void theCategoryFilterIsCaseInsensitiveAndABlankOneIsNotAFilter() throws Exception {
    repository.insert(guide(List.of(), List.of()));
    repository.insert(new SkillGuide("g-2", "other", null, "body", "writing",
        List.of(), List.of(), 1_000L, 1_000L));

    mvc.perform(get("/api/skill-guides").param("category", "DOCS"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("pdf-workflow"));
    mvc.perform(get("/api/skill-guides").param("category", "  "))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void aGuideSentWithNoIdListsIsStoredWithEmptyOnes() throws Exception {
    mvc.perform(post("/api/skill-guides").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"prose-only","body":"just words"}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skillIds.length()").value(0))
        .andExpect(jsonPath("$.mcpServerIds.length()").value(0))
        .andExpect(jsonPath("$.description").doesNotExist());
  }

  @Test
  void aNameThatCouldNotBeADirectoryIsRejected() throws Exception {
    // the guide's name becomes the umbrella skill's directory
    for (String name : List.of("../escape", "has space", ".hidden", "a/b")) {
      mvc.perform(post("/api/skill-guides").contentType(MediaType.APPLICATION_JSON).content("""
              {"name":"%s","body":"x"}
              """.formatted(name)))
          .andExpect(status().isBadRequest());
    }
    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void aGuideWithNoBodyIsRejected() throws Exception {
    mvc.perform(post("/api/skill-guides").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"empty","body":""}
            """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anUpdateAtAnIdNobodyHoldsIsA404RatherThanAnInsert() throws Exception {
    mvc.perform(put("/api/skill-guides/g-nope").contentType(MediaType.APPLICATION_JSON).content("""
            {"name":"x","body":"y"}
            """))
        .andExpect(status().isNotFound());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void deletingAGuideReachesNoAgent() throws Exception {
    repository.insert(guide(List.of(), List.of()));

    mvc.perform(delete("/api/skill-guides/g-1")).andExpect(status().isOk());
    mvc.perform(delete("/api/skill-guides/g-1")).andExpect(status().isOk());

    assertTrue(repository.findAll().isEmpty());
    verify(profiles, never()).installSkillFiles(any(), anyString(), anyString(), anyString(), any());
  }

  // ── deploy ─────────────────────────────────────────────────────────────────

  @Test
  void deployingAGuidePutsEverySkillEveryServerAndTheGuideItselfOnTheAgent() throws Exception {
    storeSkill("s-1", "pdf-tools");
    storeSkill("s-2", "log-reader");
    catalogServer("m-1", "postgres");
    repository.insert(guide(List.of("s-1", "s-2"), List.of("m-1")));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts.length()").value(4))
        .andExpect(jsonPath("$.parts[0].name").value("pdf-tools"))
        .andExpect(jsonPath("$.parts[0].status").value("deployed"))
        .andExpect(jsonPath("$.parts[2].kind").value("mcp"))
        .andExpect(jsonPath("$.parts[3].kind").value("guide"));

    verify(profiles).installSkillFiles(HOST, "c1", "ops", "pdf-tools",
        Map.of("SKILL.md", "# pdf-tools"));
    verify(mcpCatalog).connectIfAbsent(eq(HOST), eq("c1"), eq("ops"), any());
    // the umbrella names what landed, so the agent knows the set
    assertTrue(umbrella().contains("`pdf-tools`"));
    assertTrue(umbrella().contains("`postgres`"));
  }

  @Test
  void aSkillDeletedSinceTheGuideNamedItIsSkippedWithoutCostingTheRest() throws Exception {
    storeSkill("s-2", "log-reader");
    repository.insert(guide(List.of("s-gone", "s-2"), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("skipped"))
        .andExpect(jsonPath("$.parts[0].detail").value("no longer in the library"))
        .andExpect(jsonPath("$.parts[1].status").value("deployed"));

    verify(profiles).installSkillFiles(any(), anyString(), anyString(), eq("log-reader"), any());
  }

  @Test
  void anMcpServerGoneFromTheCatalogIsSkipped() throws Exception {
    when(registry.definition("m-gone")).thenThrow(new NoSuchElementException("unknown server"));
    repository.insert(guide(List.of(), List.of("m-gone")));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("skipped"))
        .andExpect(jsonPath("$.parts[0].detail").value("no longer in the catalog"));
  }

  @Test
  void anAliasAlreadyOnTheAgentIsReportedRatherThanFailingTheDeploy() throws Exception {
    // the common case, and not really a problem: the server the guide wanted is there. This
    // route used to report it as failed while a group deploy called the same event skipped,
    // because each recognised it by matching the message a conflict carried; the catalog
    // service answers the question once now.
    storeSkill("s-1", "pdf-tools");
    catalogServer("m-1", "postgres");
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any()))
        .thenReturn(Optional.empty());
    repository.insert(guide(List.of("s-1"), List.of("m-1")));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[1].status").value("skipped"))
        .andExpect(jsonPath("$.parts[1].detail").value("already connected"));

    // the skill still landed, and the umbrella went out naming the server: it is on the
    // agent, which is the only thing that document is telling the agent
    verify(profiles).installSkillFiles(any(), anyString(), anyString(), eq("pdf-tools"), any());
    assertTrue(umbrella().contains("`pdf-tools`"));
    assertTrue(umbrella().contains("`postgres`"), "an available server was not named: " + umbrella());
  }

  @Test
  void aPartThatFailedIsNotAdvertisedInTheUmbrellaSkill() throws Exception {
    // telling the agent to reach for something that is not there is worse than silence
    storeSkill("s-1", "pdf-tools");
    catalogServer("m-1", "postgres");
    when(mcpCatalog.connectIfAbsent(any(), anyString(), anyString(), any()))
        .thenThrow(new ResourceConflictException("not running"));
    repository.insert(guide(List.of("s-1"), List.of("m-1")));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk());

    assertTrue(umbrella().contains("`pdf-tools`"));
    assertTrue(!umbrella().contains("`postgres`"), "a failed server was advertised: " + umbrella());
  }

  @Test
  void oneFailedSkillDoesNotStopTheOnesAfterIt() throws Exception {
    storeSkill("s-1", "broken");
    storeSkill("s-2", "log-reader");
    when(profiles.installSkillFiles(any(), anyString(), anyString(), eq("broken"), any()))
        .thenThrow(new IllegalStateException("container is not running"));
    repository.insert(guide(List.of("s-1", "s-2"), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("failed"))
        .andExpect(jsonPath("$.parts[0].detail").value("container is not running"))
        .andExpect(jsonPath("$.parts[1].status").value("deployed"));
  }

  @Test
  void nothingIsRolledBackWhenAPartFails() throws Exception {
    // layering onto a profile the caller does not own: removing a skill that may have been
    // there before the guide ran is not this code's call
    storeSkill("s-1", "pdf-tools");
    storeSkill("s-2", "broken");
    when(profiles.installSkillFiles(any(), anyString(), anyString(), eq("broken"), any()))
        .thenThrow(new IllegalStateException("nope"));
    repository.insert(guide(List.of("s-1", "s-2"), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk());

    verify(profiles, never()).uninstallSkill(any(), anyString(), anyString(), anyString());
  }

  @Test
  void aGuideNamedAfterALibrarySkillRefusesToOverwriteIt() throws Exception {
    // both resolve to skills/pdf-workflow/, so writing the umbrella there would replace
    // that skill's own SKILL.md — on the agent, and without saying so
    storeSkill("s-1", "pdf-workflow");
    repository.insert(guide(List.of("s-1"), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[1].kind").value("guide"))
        .andExpect(jsonPath("$.parts[1].status").value("failed"))
        .andExpect(jsonPath("$.parts[1].detail").value(
            org.hamcrest.Matchers.containsString("rename one")));

    // the skill still deployed; only the umbrella was refused
    verify(profiles).installSkillFiles(any(), anyString(), anyString(), eq("pdf-workflow"), any());
  }

  @Test
  void aProfileThatCannotBeReadBackStillReturnsTheReport() throws Exception {
    // the parts are already on the agent by then; a 500 here would throw away the only
    // record of what landed
    repository.insert(guide(List.of(), List.of()));
    when(profiles.get(any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("container is not running"));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts[0].status").value("deployed"))
        .andExpect(jsonPath("$.parts[1].detail").value(
            org.hamcrest.Matchers.containsString("could not be read back")))
        .andExpect(jsonPath("$.profile").doesNotExist());
  }

  @Test
  void deployingAnUnknownGuideIsA404AndReachesNoAgent() throws Exception {
    mvc.perform(post("/api/skill-guides/g-nope/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isNotFound());

    verify(profiles, never()).installSkillFiles(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void aProfileNameThatCouldEscapeIsRejectedBeforeTheDeployRuns() throws Exception {
    repository.insert(guide(List.of(), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"hostId":"dh-1","containerId":"c1","profile":"../escape"}
                """))
        .andExpect(status().isBadRequest());

    verify(profiles, never()).installSkillFiles(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void anEmptyGuideStillWritesItsUmbrellaSkill() throws Exception {
    // prose alone is a legitimate guide — it teaches without composing anything yet
    repository.insert(guide(List.of(), List.of()));

    mvc.perform(post("/api/skill-guides/g-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parts.length()").value(1))
        .andExpect(jsonPath("$.parts[0].kind").value("guide"));

    assertTrue(umbrella().contains("Read the log, then the PDF."));
  }
}
