package io.hermes.missioncontrol.agents.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.HermesProfiles;
import io.hermes.missioncontrol.agents.HermesSetup;
import io.hermes.missioncontrol.agents.McpServerDefinition;
import io.hermes.missioncontrol.agents.ProfileSpec;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AgentProfileDto;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretInput;
import io.hermes.missioncontrol.secrets.SecretRef;
import io.hermes.missioncontrol.secrets.StoredSecret;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * Capturing a template off a running agent, and writing one back onto a profile.
 *
 * <p>Two rules here are the ones worth protecting. A capture cannot read {@code .env} values
 * back, so it records which keys were set and nothing else — a captured template must never look
 * like it carries credentials it does not have. And a half-applied template leaves a
 * misconfigured agent, so the profile is rolled back exactly when this code created it and never
 * when it belongs to the caller.
 */
class TemplateCaptureAndApplyTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///sock");
  private static final String CONTAINER = "c1";

  private final ProfileTemplateRepository repository = mock(ProfileTemplateRepository.class);
  private final HermesProfiles profiles = mock(HermesProfiles.class);
  private final HermesSetup setup = mock(HermesSetup.class);
  private final SecretCipher cipher = new SecretCipher("unit-test-key", "", true);
  private final ProfileTemplateService service =
      TemplatesWiring.service(repository, cipher, profiles, setup);

  // ── capture ─────────────────────────────────────────────────────────────

  @Test
  void aCaptureTakesTheEnabledSkillsAndOnlyTheNamesOfTheKeysThatAreSet() {
    // .env cannot be read back, so a captured secret is a placeholder: the client has to see it
    // as not-set, or an operator would deploy the template expecting a key that isn't there
    agentIs(agent("scout", List.of(
        new SkillDto("s1", "refactor", "builtin", "1", "", true),
        new SkillDto("s2", "deploy", "builtin", "1", "", false)), List.of()));
    setupIs(List.of(
        new ApiKeyStatusDto("Anthropic", "ANTHROPIC_API_KEY", true, "sk-…abcd"),
        new ApiKeyStatusDto("OpenAI", "OPENAI_API_KEY", false, null)));

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals(List.of("refactor"), captured.skills());
    SecretRef secret = captured.secrets().getFirst();
    assertEquals(List.of("ANTHROPIC_API_KEY"), captured.secrets().stream().map(SecretRef::key).toList());
    assertFalse(secret.set(), "a captured key holds no value");
    assertFalse(secret.recoverable());
  }

  @Test
  void aCapturedMcpEntryKeepsItsTransportAndIsEnabledUnlessItWasDisabled() {
    agentIs(agent("scout", List.of(), List.of(
        mcp("files", "stdio", "connected"),
        mcp("docs", "http", "disabled"))));
    setupIs(List.of());

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals(List.of("files", "docs"), captured.mcpServers().stream().map(McpServerSpec::name).toList());
    assertEquals(true, captured.mcpServers().get(0).enabled());
    assertEquals(false, captured.mcpServers().get(1).enabled());
    assertEquals("stdio", captured.mcpServers().get(0).transport());
  }

  @Test
  void aCaptureFilesItselfUnderCapturedSoSnapshotsGroupWithoutBeingNamed() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    assertEquals("captured", service.captureFromAgent(HOST, CONTAINER, "scout", "ops").category());
  }

  @Test
  void aCaptureIsNamedAfterTheAgentWhenTheOperatorGivesNoName() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    assertEquals("scout-template", service.captureFromAgent(HOST, CONTAINER, "scout", null).name());
    assertEquals("scout-template", service.captureFromAgent(HOST, CONTAINER, "scout", "  ").name());
  }

  @Test
  void aNameAlreadyTakenGetsASuffixRatherThanFailingTheCapture() {
    // capture is a one-click action off an agent page; refusing it over a name the operator never
    // typed would be a dead end
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());
    when(repository.existsByName("scout-template")).thenReturn(true);
    when(repository.existsByName("scout-template-2")).thenReturn(true);

    assertEquals("scout-template-3", service.captureFromAgent(HOST, CONTAINER, "scout", null).name());
  }

  @Test
  void aCaptureRecordsWhatItCameFromAndTheModelItWasRunning() {
    agentIs(agent("scout", List.of(), List.of()));
    setupIs(List.of());

    ProfileTemplateDto captured = service.captureFromAgent(HOST, CONTAINER, "scout", "ops");

    assertEquals("Captured from scout", captured.description());
    assertEquals("anthropic", captured.provider());
    assertEquals("claude-opus-5", captured.model());
    assertEquals("/work", captured.cwd());
    assertEquals("be useful", captured.soul());
    assertEquals("remembered", captured.memory());
    // baseUrl is not readable off a live profile, so it is captured empty rather than guessed
    assertEquals("", captured.baseUrl());
    verify(repository).insert(any(ProfileTemplate.class));
  }

  // ── deploy: the profile this code owns ──────────────────────────────────

  @Test
  void deployingCreatesTheProfileFromTheTemplatesOwnModelSettings() {
    templateIs(template(t -> {
      t.provider = "openai";
      t.model = "gpt-5.2";
      t.baseUrl = "https://gateway.test/v1";
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    ArgumentCaptor<ProfileSpec> created = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), created.capture());
    assertEquals("openai", created.getValue().provider());
    assertEquals("gpt-5.2", created.getValue().model());
    assertEquals("https://gateway.test/v1", created.getValue().baseUrl());
    assertEquals("scout", created.getValue().name());
  }

  @Test
  void aTemplateWithNoModelSettingsFallsBackToTheHermesDefaults() {
    // a template captured before these fields existed, or authored empty, still has to deploy
    templateIs(template(t -> {
      t.provider = "";
      t.model = "  ";
      t.baseUrl = "";
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    ArgumentCaptor<ProfileSpec> created = ArgumentCaptor.forClass(ProfileSpec.class);
    verify(profiles).create(eq(HOST), created.capture());
    assertEquals("nous", created.getValue().provider());
    assertEquals("Hermes-4-405B", created.getValue().model());
    assertNull(created.getValue().baseUrl(), "a blank base HOST must not be sent as an empty string");
  }

  @Test
  void everyPartOfATemplateIsWrittenAndTheEmptyPartsAreSkipped() {
    templateIs(template(t -> {
      t.soul = "be useful";
      t.memory = "   ";
      // List.of rejects nulls; a stored template can legitimately carry them
      t.skills = Arrays.asList("refactor", "  ", null);
      t.mcpServers = Arrays.asList(mcpSpec("files"), mcpSpec("  "), null);
      t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", cipher.encrypt("sk-ant-real")));
    }));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    InOrder order = inOrder(profiles, setup);
    order.verify(profiles).create(eq(HOST), any());
    order.verify(profiles).updateSoul(HOST, CONTAINER, "scout", "be useful");
    order.verify(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    order.verify(profiles).addMcpServer(eq(HOST), eq(CONTAINER), eq("scout"), any(McpServerDefinition.class));
    order.verify(setup).putEnv(HOST, CONTAINER, "scout",
        List.of(new EnvEntry("ANTHROPIC_API_KEY", "sk-ant-real")));
    // a blank memory is not a memory: writing it would overwrite whatever the profile had
    verify(profiles, never()).updateMemory(any(), anyString(), anyString(), anyString());
    verify(profiles, never()).installSkill(HOST, CONTAINER, "scout", "");
  }

  @Test
  void aSecretThatNoLongerDecryptsIsLeftOutRatherThanWrittenAsAnEmptyVariable() {
    // an empty ANTHROPIC_API_KEY in .env is worse than a missing one: the agent starts and fails
    // its first call with an auth error instead of saying the key is not configured
    String foreign = new SecretCipher("some-other-key", "", true).encrypt("sk-ant-real");
    templateIs(template(t -> t.secrets = List.of(
        new StoredSecret("ANTHROPIC_API_KEY", foreign),
        new StoredSecret("OPENAI_API_KEY", null))));
    when(profiles.get(HOST, CONTAINER, "scout")).thenReturn(agent("scout", List.of(), List.of()));

    service.deploy("pt-1", HOST, CONTAINER, "scout");

    verify(setup, never()).putEnv(any(), anyString(), anyString(), anyList());
  }

  @Test
  void aFailureWhileApplyingDropsTheProfileThisCallCreated() {
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");

    assertEquals("skill not found", assertThrows(IllegalStateException.class,
        () -> service.deploy("pt-1", HOST, CONTAINER, "scout")).getMessage());

    // all or nothing: a profile with a soul and no skills is not what the operator asked for
    verify(profiles).delete(HOST, CONTAINER, "scout");
  }

  @Test
  void aRollbackThatItselfFailsIsAttachedToTheOriginalFailureNotSubstitutedForIt() {
    // the operator needs the reason the deploy failed; the cleanup problem is secondary
    templateIs(template(t -> t.skills = List.of("refactor")));
    IllegalStateException original = new IllegalStateException("skill not found");
    doThrow(original).when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    doThrow(new IllegalStateException("container is gone"))
        .when(profiles).delete(HOST, CONTAINER, "scout");

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
        () -> service.deploy("pt-1", HOST, CONTAINER, "scout"));

    assertSame(original, thrown);
    assertEquals("container is gone", thrown.getSuppressed()[0].getMessage());
  }

  // ── layer: a profile someone else owns ──────────────────────────────────

  @Test
  void layeringOntoAnExistingProfileNeverDeletesItOnFailure() {
    // dropping an agent the caller already had is not this code's call
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");

    assertThrows(IllegalStateException.class, () -> service.applyExisting("pt-1", HOST, CONTAINER, "scout"));

    verify(profiles, never()).delete(any(), anyString(), anyString());
    verify(profiles, never()).create(any(), any());
  }

  @Test
  void theCreateFlowOwnsItsBareProfileAndRollsItBack() {
    // createFromTemplate builds the profile from the caller's model settings, not the template's,
    // so it creates it itself — and therefore owns the rollback
    templateIs(template(t -> t.skills = List.of("refactor")));
    doThrow(new IllegalStateException("skill not found"))
        .when(profiles).installSkill(HOST, CONTAINER, "scout", "refactor");
    ProfileSpec spec = new ProfileSpec(
        CONTAINER, "scout", "anthropic", "claude-opus-5", null, null, null, null);

    assertThrows(IllegalStateException.class, () -> service.createFromTemplate("pt-1", HOST, spec));

    verify(profiles).createProfileBare(HOST, spec);
    verify(profiles).delete(HOST, CONTAINER, "scout");
    verify(profiles, never()).create(any(), any());
  }

  // ── stored secrets on the way in ────────────────────────────────────────

  @Test
  void aBlankSecretValueKeepsWhatIsStoredAndReSealsIt() {
    // the editor never receives ciphertext, so blank is how it says 'unchanged'
    String stored = cipher.encrypt("sk-ant-real");
    ProfileTemplate existing = template(t -> t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", stored)));
    when(repository.findById("pt-1")).thenReturn(Optional.of(existing));

    ProfileTemplateDto updated = service.update("pt-1", upsert(List.of(
        new SecretInput("ANTHROPIC_API_KEY", "  "))));

    assertEquals(List.of("ANTHROPIC_API_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
    assertTrue(updated.secrets().getFirst().set());
    assertTrue(updated.secrets().getFirst().recoverable());

    // a save is also the rotation opportunity — a kept secret comes back under the current
    // key rather than riding on whatever wrote it, which is what retires MC_SECRET_KEY_PREVIOUS
    ArgumentCaptor<ProfileTemplate> saved = ArgumentCaptor.forClass(ProfileTemplate.class);
    verify(repository).update(saved.capture());
    String resealed = saved.getValue().secrets().getFirst().enc();
    assertFalse(stored.equals(resealed), "the envelope was carried over verbatim");
    assertEquals("sk-ant-real", cipher.decrypt(resealed));
  }

  @Test
  void aBlankSecretValueWithNothingStoredIsRefusedRatherThanDropped() {
    // Dropping it reported a success that did not happen: the template came back without the
    // key, and the next deploy produced an agent missing a credential it appeared to carry.
    // The MCP catalog already refused this; the two paths had drifted apart.
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    assertEquals("secret value is required: OPENAI_API_KEY",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("OPENAI_API_KEY", ""))))).getMessage());

    verify(repository, never()).update(any());
  }

  @Test
  void aCapturedPlaceholderSurvivesASaveThatDoesNotFillItIn() {
    // a capture records which keys were set and never their values, so a placeholder has no
    // envelope at all — refusing it would make every captured template unsaveable until the
    // operator typed in every credential at once
    ProfileTemplate captured =
        template(t -> t.secrets = List.of(new StoredSecret("ANTHROPIC_API_KEY", null)));
    when(repository.findById("pt-1")).thenReturn(Optional.of(captured));

    ProfileTemplateDto updated = service.update("pt-1", upsert(List.of(
        new SecretInput("ANTHROPIC_API_KEY", ""))));

    assertEquals(List.of("ANTHROPIC_API_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
    assertFalse(updated.secrets().getFirst().set(), "a placeholder must not look like a stored key");
    assertFalse(updated.secrets().getFirst().recoverable());
  }

  @Test
  void aSecretKeyMustLookLikeAnEnvironmentVariableAndItsValueIsBounded() {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    assertEquals("invalid secret key: lower_case",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("lower_case", "x"))))).getMessage());
    assertEquals("invalid secret key: 1LEADING",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("1LEADING", "x"))))).getMessage());
    assertEquals("secret value too large for BIG_KEY",
        assertThrows(IllegalArgumentException.class, () -> service.update("pt-1",
            upsert(List.of(new SecretInput("BIG_KEY", "x".repeat(65_537)))))).getMessage());
    verify(repository, never()).update(any());
  }

  @Test
  void aSecretEntryWithNoKeyAtAllIsSkipped() {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template(t -> { })));

    ProfileTemplateDto updated = service.update("pt-1", upsert(Arrays.asList(
        null, new SecretInput(null, "x"), new SecretInput("  ", "x"),
        new SecretInput("GOOD_KEY", "value"))));

    assertEquals(List.of("GOOD_KEY"), updated.secrets().stream().map(SecretRef::key).toList());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private void agentIs(AgentProfileDto agent) {
    when(profiles.get(HOST, CONTAINER, agent.name())).thenReturn(agent);
  }

  private void setupIs(List<ApiKeyStatusDto> apiKeys) {
    when(setup.setup(eq(HOST), eq(CONTAINER), anyString())).thenReturn(
        new AgentSetupDto("/opt/data/.env", true, apiKeys, List.of(), List.of(), List.of()));
  }

  private void templateIs(ProfileTemplate template) {
    when(repository.findById("pt-1")).thenReturn(Optional.of(template));
  }

  private static AgentProfileDto agent(String name, List<SkillDto> skills, List<AgentMcpServerDto> mcp) {
    return new AgentProfileDto("c1:" + name, CONTAINER, name, "role", "idle", "anthropic",
        "claude-opus-5", "sk-…abcd", "/work", "be useful", "remembered", "model: opus\n",
        skills, mcp, List.of(), 0L);
  }

  private static AgentMcpServerDto mcp(String name, String transport, String status) {
    return new AgentMcpServerDto(name, name, transport, !"disabled".equals(status), status,
        0, null, null, null, "http://x:1/mcp", null, null);
  }

  private static McpServerSpec mcpSpec(String name) {
    return new McpServerSpec(name, "http", "http://x:1/mcp", null, null, true);
  }

  /** A template with everything empty, so each test sets only the part it is about. */
  private static ProfileTemplate template(java.util.function.Consumer<Fields> tweak) {
    Fields fields = new Fields();
    tweak.accept(fields);
    return new ProfileTemplate("pt-1", "ops", "desc", "ops", fields.provider, fields.model, fields.baseUrl,
        "/work", fields.soul, fields.memory, fields.skills, fields.mcpServers, fields.secrets, 1L, 1L);
  }

  private static final class Fields {
    String provider = "anthropic";
    String model = "claude-opus-5";
    String baseUrl = "";
    String soul = "";
    String memory = "";
    List<String> skills = List.of();
    List<McpServerSpec> mcpServers = List.of();
    List<StoredSecret> secrets = List.of();
  }

  private static UpsertProfileTemplateRequest upsert(List<SecretInput> secrets) {
    return new UpsertProfileTemplateRequest("ops", "desc", "ops", "anthropic", "claude-opus-5", "",
        "/work", "soul", "memory", List.of(), List.of(), secrets);
  }
}
