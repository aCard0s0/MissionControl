package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesModelConfig.ConfigInfo;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.agents.api.SessionDto;
import io.hermes.missioncontrol.agents.api.SkillContentDto;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The facade every agent endpoint calls, and the wiring it is responsible for.
 *
 * <p>{@link HermesProfiles} owns almost no logic — each method resolves the profile's paths and
 * hands off to the collaborator that owns the concern. What it does own is which collaborator,
 * with which arguments, and whether the caller gets a refreshed profile back: a mutation that
 * forgets the re-read hands the dashboard the state from before the write, and a path built from
 * the wrong name edits another agent. That is what this pins.
 */
class HermesProfilesDelegationTest {

  private static final String URL = "unix:///sock";
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "scout";
  private static final String DIR = "/opt/data/profiles/scout";

  private HermesContainerFiles files;
  private HermesEnvFile env;
  private HermesModelConfig modelConfig;
  private HermesSkills skills;
  private HermesProfileMcp mcp;
  private HermesSessions sessions;
  private HermesGatewayLogs gatewayLogs;
  private HermesIntegrations integrations;
  private HermesProfiles profiles;

  @BeforeEach
  void setUp() {
    files = mock(HermesContainerFiles.class);
    env = mock(HermesEnvFile.class);
    modelConfig = mock(HermesModelConfig.class);
    skills = mock(HermesSkills.class);
    mcp = mock(HermesProfileMcp.class);
    sessions = mock(HermesSessions.class);
    gatewayLogs = mock(HermesGatewayLogs.class);
    integrations = mock(HermesIntegrations.class);
    profiles = new HermesProfiles(files, env, modelConfig, skills, mcp, sessions, gatewayLogs,
        integrations, new ProfileInventory(files));

    when(files.requireProfileDir(URL, CONTAINER, PROFILE)).thenReturn(DIR);
    when(files.readFile(anyString(), anyString(), anyString())).thenReturn("");
    when(modelConfig.parseConfig(any())).thenReturn(new ConfigInfo("anthropic", "claude-opus-5", "/work"));
  }

  // ── reading ─────────────────────────────────────────────────────────────

  @Test
  void readingOneProfileAssemblesItFromEveryCollaborator() {
    when(files.readFile(URL, CONTAINER, DIR + "/SOUL.md")).thenReturn("be useful");
    when(files.readFile(URL, CONTAINER, DIR + "/MEMORY.md")).thenReturn("remembered");

    AgentProfileDto profile = profiles.get(URL, CONTAINER, PROFILE);

    assertEquals(PROFILE, profile.name());
    assertEquals("Profile", profile.role());
    assertEquals("anthropic", profile.provider());
    assertEquals("claude-opus-5", profile.model());
    assertEquals("/work", profile.cwd());
    assertEquals("be useful", profile.soul());
    assertEquals("remembered", profile.memoryMd());
    verify(skills).list(URL, CONTAINER, PROFILE, Map.of());
    verify(mcp).list(URL, CONTAINER, PROFILE, Map.of());
    verify(integrations).list(URL, CONTAINER, PROFILE);
  }

  @Test
  void theDefaultProfileIsLabelledAsSuchAndReadFromTheHermesHome() {
    when(files.requireProfileDir(URL, CONTAINER, "default")).thenReturn("/opt/data");

    AgentProfileDto profile = profiles.get(URL, CONTAINER, "default");

    assertEquals("Default profile", profile.role());
    verify(files).readFile(URL, CONTAINER, "/opt/data/config.yaml");
  }

  @Test
  void listingReadsTheDefaultProfileAndEveryValidlyNamedDirectory() {
    when(files.dirExists(URL, CONTAINER, "/opt/data")).thenReturn(true);
    when(files.exec(anyString(), anyString(), any())).thenReturn(
        new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(
            0, "scout\ndefault\n../escape\n\nscribe\n", ""));
    when(files.requireProfileDir(anyString(), anyString(), anyString())).thenReturn(DIR);

    List<AgentProfileDto> listed = profiles.list(URL, CONTAINER);

    // 'default' is added once from the home directory, not twice; a name that could escape the
    // profiles directory is dropped before it is concatenated into a container path
    assertEquals(List.of("default", "scout", "scribe"),
        listed.stream().map(AgentProfileDto::name).toList());
  }

  @Test
  void aContainerWithNoHermesHomeListsOnlyItsNamedProfiles() {
    when(files.dirExists(URL, CONTAINER, "/opt/data")).thenReturn(false);
    when(files.exec(anyString(), anyString(), any())).thenReturn(
        new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(0, "scout\n", ""));
    when(files.requireProfileDir(anyString(), anyString(), anyString())).thenReturn(DIR);

    assertEquals(List.of("scout"),
        profiles.list(URL, CONTAINER).stream().map(AgentProfileDto::name).toList());
  }

  @Test
  void aStoppedContainerListsNothingRatherThanFailingTheAgentsPage() {
    // Docker answers 409 when a stale dashboard client asks to exec in a stopped container;
    // inventory is simply unavailable until it restarts
    when(files.dirExists(anyString(), anyString(), anyString()))
        .thenThrow(new com.github.dockerjava.api.exception.ConflictException("container not running"));

    assertTrue(profiles.list(URL, CONTAINER).isEmpty());
  }

  // ── documents ───────────────────────────────────────────────────────────

  @Test
  void theSoulAndMemoryAreWrittenIntoTheProfileDirectory() {
    profiles.updateSoul(URL, CONTAINER, PROFILE, "be useful");
    profiles.updateMemory(URL, CONTAINER, PROFILE, null);

    verify(files).writeFile(URL, CONTAINER, DIR + "/SOUL.md", "be useful");
    // a null document is written as empty rather than as the string "null"
    verify(files).writeFile(URL, CONTAINER, DIR + "/MEMORY.md", "");
  }

  @Test
  void aConfigThatIsNotAMappingIsRefusedBeforeItOverwritesTheFile() {
    assertEquals("config.yaml must be a YAML mapping",
        assertThrows(IllegalArgumentException.class,
            () -> profiles.updateConfig(URL, CONTAINER, PROFILE, "- a list\n")).getMessage());

    verify(files, never()).writeFile(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void aValidConfigIsWrittenAndTheProfileIsReadBack() {
    AgentProfileDto updated = profiles.updateConfig(URL, CONTAINER, PROFILE, "model: opus\n");

    verify(files).writeFile(URL, CONTAINER, DIR + "/config.yaml", "model: opus\n");
    assertEquals(PROFILE, updated.name(), "the caller gets the state after the write");
  }

  // ── skills ──────────────────────────────────────────────────────────────

  @Test
  void everySkillEndpointHandsOffToTheSkillsCollaboratorAndReturnsTheFreshProfile() {
    when(skills.readContent(URL, CONTAINER, PROFILE, "refactor"))
        .thenReturn(new SkillContentDto("refactor", DIR + "/skills/refactor", "body", List.of()));

    assertEquals(PROFILE, profiles.setSkillEnabled(URL, CONTAINER, PROFILE, "refactor", false).name());
    assertEquals(PROFILE, profiles.installSkill(URL, CONTAINER, PROFILE, "refactor").name());
    assertEquals(PROFILE, profiles.uninstallSkill(URL, CONTAINER, PROFILE, "refactor").name());
    assertEquals(PROFILE, profiles.updateSkillContent(URL, CONTAINER, PROFILE, "refactor", "body").name());
    assertEquals("body", profiles.readSkillContent(URL, CONTAINER, PROFILE, "refactor").body());

    verify(skills).setEnabled(URL, CONTAINER, PROFILE, "refactor", false);
    verify(skills).install(URL, CONTAINER, PROFILE, "refactor");
    verify(skills).uninstall(URL, CONTAINER, PROFILE, "refactor");
    verify(skills).updateContent(URL, CONTAINER, PROFILE, "refactor", "body");
  }

  // ── mcp ─────────────────────────────────────────────────────────────────

  @Test
  void everyMcpEndpointHandsOffToTheMcpCollaboratorAndReturnsTheFreshProfile() {
    AddMcpServerRequest request = new AddMcpServerRequest("files", "http", "http://x:1/mcp", null, null, true);
    when(mcp.test(URL, CONTAINER, PROFILE, "files"))
        .thenReturn(new McpTestResult("files", "connected", 3, 12L, null, 1L));

    assertEquals(PROFILE, profiles.addMcpServer(URL, CONTAINER, PROFILE, request).name());
    assertEquals(PROFILE, profiles.updateMcpServer(URL, CONTAINER, PROFILE, "files", request).name());
    assertEquals(PROFILE, profiles.setMcpServerEnabled(URL, CONTAINER, PROFILE, "files", false).name());
    assertEquals(PROFILE, profiles.removeMcpServer(URL, CONTAINER, PROFILE, "files").name());
    assertEquals("connected", profiles.testMcpServer(URL, CONTAINER, PROFILE, "files").status());

    verify(mcp).add(URL, CONTAINER, PROFILE, request);
    verify(mcp).update(URL, CONTAINER, PROFILE, "files", request);
    verify(mcp).setEnabled(URL, CONTAINER, PROFILE, "files", false);
    verify(mcp).remove(URL, CONTAINER, PROFILE, "files");
  }

  // ── reads that pass straight through ────────────────────────────────────

  @Test
  void integrationsLogsAndSessionsAreReadFromTheirOwnCollaborators() {
    when(gatewayLogs.read(URL, CONTAINER, PROFILE, 50))
        .thenReturn(List.of(new LogLineDto(1L, "info", PROFILE, "started")));
    when(sessions.list(URL, CONTAINER, PROFILE))
        .thenReturn(List.of(new SessionDto("s-1", "first", "cli", 1L, 2, "done")));
    when(sessions.readMessages(URL, CONTAINER, PROFILE, "s-1")).thenReturn("[]");

    assertTrue(profiles.integrations(URL, CONTAINER, PROFILE).isEmpty());
    assertEquals("started", profiles.logs(URL, CONTAINER, PROFILE, 50).getFirst().msg());
    assertEquals("s-1", profiles.listSessions(URL, CONTAINER, PROFILE).getFirst().id());
    assertEquals("[]", profiles.readSessionMessages(URL, CONTAINER, PROFILE, "s-1"));

    profiles.deleteSession(URL, CONTAINER, PROFILE, "s-1");
    verify(sessions).delete(URL, CONTAINER, PROFILE, "s-1");
    verify(integrations).list(URL, CONTAINER, PROFILE);
    verify(gatewayLogs).read(URL, CONTAINER, PROFILE, 50);
  }
}
