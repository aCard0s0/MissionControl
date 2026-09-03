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
import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.SkillFilesDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.support.SqliteTestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The skill library endpoints over a real database.
 *
 * <p>The branch in {@code deploy} is what this class exists for: a {@code hub} row defers to
 * {@code hermes skills install} and a {@code local} row writes its files, and picking the
 * wrong one is silent — an install of a name the Skills Hub has never heard of fails on the
 * agent, and writing files for a hub skill would plant a stale copy beside the real one.
 *
 * <p>{@link SkillRepositoryTest} covers the SQL. What is only reachable from here is the
 * normalization the controller owns and the rejections that keep a row that could never
 * deploy usefully out of the library in the first place.
 */
class SkillControllerTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-1", "unix:///var/run/docker.sock");

  private SqliteTestDatabase database;
  private SkillRepository repository;
  private HermesProfiles profiles;
  private UpstreamCheck upstream;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    database = SqliteTestDatabase.open();
    repository = new SkillRepository(database.jdbc(), new ObjectMapper());
    profiles = mock(HermesProfiles.class);
    HostService hosts = mock(HostService.class);
    when(hosts.requireConnected(anyString())).thenReturn(HOST);
    upstream = mock(UpstreamCheck.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new SkillController(
            repository, new SkillDeployer(profiles), upstream, profiles, hosts))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @AfterEach
  void tearDown() throws Exception {
    database.close();
  }

  private static String localBody(String name) {
    return """
        {"kind":"local","name":"%s","description":"reads pdfs","category":"Docs",
         "files":[{"path":"SKILL.md","body":"# pdf"},{"path":"scripts/run.sh","body":"echo"}]}
        """.formatted(name);
  }

  private static String target() {
    return """
        {"hostId":"dh-1","containerId":"c1","profile":"ops"}
        """;
  }

  // ── create / update ────────────────────────────────────────────────────────

  @Test
  void aCreatedSkillIsPersistedAndComesBackFromTheListEndpoint() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content(localBody("pdf")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("pdf"))
        .andExpect(jsonPath("$.category").value("docs"))   // folded to lower case
        .andExpect(jsonPath("$.files.length()").value(2));

    mvc.perform(get("/api/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void theCategoryFilterIsCaseInsensitiveAndABlankOneIsNotAFilter() throws Exception {
    repository.insert(new Skill("s-1", Skill.HUB, "pdf", null, "docs", null, null,
        List.of(), 1_000L, 1_000L));
    repository.insert(new Skill("s-2", Skill.HUB, "web", null, "research", null, null,
        List.of(), 1_000L, 1_000L));

    mvc.perform(get("/api/skills").param("category", "DOCS"))
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].name").value("pdf"));
    mvc.perform(get("/api/skills").param("category", "  "))
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void aDuplicateFilePathIsSettledBeforeTheRowIsStored() throws Exception {
    // two entries for one path would deploy in an order nothing defines; the first wins
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[
              {"path":"SKILL.md","body":"first"},
              {"path":"SKILL.md","body":"second"}]}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(1))
        .andExpect(jsonPath("$.files[0].body").value("first"));
  }

  @Test
  void aBlankFilePathIsRejectedByValidationRatherThanQuietlyDropped() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[
              {"path":"SKILL.md","body":"x"},{"path":"  ","body":"y"}]}
            """))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void aNullEntryInTheFileArrayIsIgnoredRatherThanCrashing() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[{"path":"SKILL.md","body":"x"},null]}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files.length()").value(1));
  }

  @Test
  void aFileSentWithNoBodyIsStoredAsEmptyRatherThanNull() throws Exception {
    // an empty file is a legitimate thing to deploy; a null would reach the exec as "null"
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[{"path":"SKILL.md"}]}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.files[0].body").value(""));
  }

  @Test
  void aBlankCategoryBecomesGeneral() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[{"path":"SKILL.md","body":"# pdf"}]}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value(SkillController.DEFAULT_CATEGORY));
  }

  @Test
  void aLocalSkillWithoutASkillMdIsRejectedAndNothingIsWritten() throws Exception {
    // hermes finds a skill by its SKILL.md, so this row could be saved and deployed and
    // still leave the agent with nothing it can load
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[{"path":"scripts/run.sh","body":"echo"}]}
            """))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void aHubSkillCarryingFilesIsRejected() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"hub","name":"pdf","files":[{"path":"SKILL.md","body":"# pdf"}]}
            """))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void aFilePathThatCouldNotBeWrittenIsRejectedAtSaveRatherThanAtDeploy() throws Exception {
    // the seam guard stops any of these reaching a container, but a row that can never
    // deploy has no business in the library — an operator would only find out on the
    // click that was supposed to work
    for (String path : List.of(
        "../../../etc/passwd", "..", "a/../../b", "/etc/passwd", "a//b", "a/",
        "..\\\\..\\\\windows", ".hidden/SKILL.md", "-rf", "a/b/c/d")) {
      mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
              {"kind":"local","name":"evil","files":[
                {"path":"SKILL.md","body":"x"},{"path":"%s","body":"pwned"}]}
              """.formatted(path)))
          .andExpect(status().isBadRequest());
    }
    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void aThreeSegmentPathIsAcceptedBecauseTheReaderCanStillSeeIt() throws Exception {
    // the cap is bounded by `find -maxdepth 3` in listSkillFiles, not by taste
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"local","name":"pdf","files":[
              {"path":"SKILL.md","body":"x"},{"path":"refs/a/b.md","body":"notes"}]}
            """))
        .andExpect(status().isOk());
  }

  @Test
  void theResponseCarriesOnlyTheDocumentedSkillKeys() throws Exception {
    // a bean-shaped helper on the record would be serialized as a wire field; this record
    // *is* the response body, and docs/api.md pins its shape
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content(localBody("pdf")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.local").doesNotExist());
  }

  @Test
  void aKindOutsideHubAndLocalIsRejected() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
            {"kind":"sideload","name":"pdf"}
            """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aNameThatCouldNotBeADirectoryIsRejectedByBeanValidation() throws Exception {
    for (String name : List.of("../escape", "has space", ".hidden", "-rf", "a/b")) {
      mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content("""
              {"kind":"hub","name":"%s"}
              """.formatted(name)))
          .andExpect(status().isBadRequest());
    }
    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void anUpdateAtAnIdNobodyHoldsIsA404RatherThanAnInsert() throws Exception {
    mvc.perform(put("/api/skills/s-nope").contentType(MediaType.APPLICATION_JSON)
            .content(localBody("pdf")))
        .andExpect(status().isNotFound());

    assertTrue(repository.findAll().isEmpty());
  }

  @Test
  void deleteIsIdempotentAndLeavesAnyDeployedCopyAlone() throws Exception {
    mvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content(localBody("pdf")))
        .andExpect(status().isOk());
    String id = repository.findAll().getFirst().id();

    mvc.perform(delete("/api/skills/" + id)).andExpect(status().isOk());
    mvc.perform(delete("/api/skills/" + id)).andExpect(status().isOk());

    assertTrue(repository.findAll().isEmpty());
    // a library row is a stamp, not a live link — nothing reaches an agent on delete
    verify(profiles, never()).uninstallSkill(any(), anyString(), anyString(), anyString());
  }

  // ── deploy: the branch ─────────────────────────────────────────────────────

  @Test
  void deployingALocalSkillWritesItsFilesAndNeverShellsHermesInstall() throws Exception {
    repository.insert(new Skill("s-1", Skill.LOCAL, "pdf", null, "docs", null, null,
        List.of(new SkillFile("SKILL.md", "# pdf"), new SkillFile("scripts/run.sh", "echo")),
        1_000L, 1_000L));
    when(profiles.installSkillFiles(any(), anyString(), anyString(), anyString(), any()))
        .thenReturn(mock(AgentProfileDto.class));

    mvc.perform(post("/api/skills/s-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk());

    verify(profiles).installSkillFiles(HOST, "c1", "ops", "pdf",
        Map.of("SKILL.md", "# pdf", "scripts/run.sh", "echo"));
    verify(profiles, never()).installSkill(any(), anyString(), anyString(), anyString());
  }

  @Test
  void deployingAHubSkillShellsHermesInstallAndWritesNoFiles() throws Exception {
    // there is nothing to write: the Skills Hub owns the content, and a copy stored here
    // would go stale the moment the Hub moves
    repository.insert(new Skill("s-1", Skill.HUB, "pdf", null, "docs",
        "https://example.test/pdf", null, List.of(), 1_000L, 1_000L));
    when(profiles.installSkill(any(), anyString(), anyString(), anyString()))
        .thenReturn(mock(AgentProfileDto.class));

    mvc.perform(post("/api/skills/s-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isOk());

    verify(profiles).installSkill(HOST, "c1", "ops", "pdf");
    verify(profiles, never()).installSkillFiles(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void deployingAnUnknownSkillIsA404AndReachesNoAgent() throws Exception {
    mvc.perform(post("/api/skills/s-nope/deploy").contentType(MediaType.APPLICATION_JSON)
            .content(target()))
        .andExpect(status().isNotFound());

    verify(profiles, never()).installSkill(any(), anyString(), anyString(), anyString());
    verify(profiles, never()).installSkillFiles(any(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void aProfileNameThatCouldEscapeIsRejectedBeforeTheDeployRuns() throws Exception {
    repository.insert(new Skill("s-1", Skill.HUB, "pdf", null, "docs", null, null,
        List.of(), 1_000L, 1_000L));

    mvc.perform(post("/api/skills/s-1/deploy").contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"hostId":"dh-1","containerId":"c1","profile":"../escape"}
                """))
        .andExpect(status().isBadRequest());

    verify(profiles, never()).installSkill(any(), anyString(), anyString(), anyString());
  }

  // ── import ─────────────────────────────────────────────────────────────────

  @Test
  void importingASkillOffAnAgentStoresItAsLocalWithItsFiles() throws Exception {
    when(profiles.readSkillFiles(any(), anyString(), anyString(), eq("curated")))
        .thenReturn(new SkillFilesDto(Map.of("SKILL.md", "# curated", "notes.md", "why"), List.of("logo.png")));

    mvc.perform(post("/api/skills/import").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-1","containerId":"c1","profile":"ops","skillName":"curated"}
            """))
        .andExpect(status().isOk())
        // always local: a skill read off a disk has no hub id to install by, even when it
        // originally came from the Hub
        .andExpect(jsonPath("$.skill.kind").value(Skill.LOCAL))
        .andExpect(jsonPath("$.skill.files.length()").value(2))
        .andExpect(jsonPath("$.skipped[0]").value("logo.png"));

    assertEquals(1, repository.findAll().size());
  }

  @Test
  void reimportingTheSameNameUpdatesTheRowRatherThanCollidingOnTheUniqueIndex() throws Exception {
    repository.insert(new Skill("s-1", Skill.LOCAL, "curated", "kept", "docs",
        "https://example.test/c", "1.0", List.of(new SkillFile("SKILL.md", "# old")),
        1_000L, 1_000L));
    when(profiles.readSkillFiles(any(), anyString(), anyString(), anyString()))
        .thenReturn(new SkillFilesDto(Map.of("SKILL.md", "# new"), List.of()));

    mvc.perform(post("/api/skills/import").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-1","containerId":"c1","profile":"ops","skillName":"curated"}
            """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skill.id").value("s-1"));

    assertEquals(1, repository.findAll().size());
    Skill updated = repository.find("s-1").orElseThrow();
    assertEquals("# new", updated.files().getFirst().body());
    // the description and repo link an operator typed survive a re-import
    assertEquals("kept", updated.description());
    assertEquals("https://example.test/c", updated.repoUrl());
    assertEquals(1_000L, updated.createdAt());
  }

  @Test
  void importingASkillWithoutASkillMdIsRejected() throws Exception {
    when(profiles.readSkillFiles(any(), anyString(), anyString(), anyString()))
        .thenReturn(new SkillFilesDto(Map.of("notes.md", "why"), List.of()));

    mvc.perform(post("/api/skills/import").contentType(MediaType.APPLICATION_JSON).content("""
            {"hostId":"dh-1","containerId":"c1","profile":"ops","skillName":"odd"}
            """))
        .andExpect(status().isBadRequest());

    assertTrue(repository.findAll().isEmpty());
  }
}
