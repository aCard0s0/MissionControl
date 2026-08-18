package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.MessagingStatusDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Setup reporting merges two sources — the profile {@code .env} and the {@code hermes
 * status} report — and its failure mode is quiet: a parsing slip makes every provider look
 * unconfigured, which is indistinguishable from a genuinely empty agent.
 *
 * <p>The status fixtures here are hand-written to the format the parser documents. They pin
 * the parser's own rules; they are not evidence about what the hermes CLI actually emits, so
 * a CLI format change is still a live risk this suite cannot see.
 */
class HermesSetupTest {

  private static final String URL = "unix:///var/run/docker.sock";
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "default";

  private HermesProfiles profiles;
  private HermesSetup setup;

  @BeforeEach
  void setUp() {
    profiles = mock(HermesProfiles.class);
    setup = new HermesSetup(profiles);
    when(profiles.profileDir(PROFILE)).thenReturn("/opt/data/profiles/default");
    when(profiles.fileExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(profiles.readFile(anyString(), anyString(), anyString())).thenReturn("");
    statusOutput("");
  }

  private void statusOutput(String stdout) {
    when(profiles.exec(anyString(), anyString(), any()))
        .thenReturn(new io.hermes.missioncontrol.docker.DockerExecService.ExecResult(0, stdout, ""));
  }

  private void envFile(String contents) {
    when(profiles.readFile(URL, CONTAINER, "/opt/data/profiles/default/.env")).thenReturn(contents);
  }

  private AgentSetupDto run() {
    return setup.setup(URL, CONTAINER, PROFILE);
  }

  private static ApiKeyStatusDto key(AgentSetupDto dto, String envVar) {
    return dto.apiKeys().stream().filter(k -> k.envVar().equals(envVar)).findFirst().orElseThrow();
  }

  @Test
  void aKeyInTheEnvIsReportedSetAndMaskedToItsLastFourCharacters() {
    envFile("ANTHROPIC_API_KEY=sk-ant-secret-value-1234");

    ApiKeyStatusDto anthropic = key(run(), "ANTHROPIC_API_KEY");
    assertTrue(anthropic.set());
    assertEquals("...1234", anthropic.masked());
  }

  @Test
  void aKeyTooShortToHaveAHiddenPartRevealsNoneOfItsCharacters() {
    // a mask that appends the whole value discloses the secret it exists to hide, and a
    // short key is exactly the case where the suffix *is* the whole key
    envFile("OPENAI_API_KEY=abc");

    assertEquals("...", key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void anAlternateVariableIsUsedWhenThePrimaryIsAbsent() {
    // ANTHROPIC_TOKEN is the documented fallback for ANTHROPIC_API_KEY
    envFile("ANTHROPIC_TOKEN=sk-ant-fallback-9876");

    ApiKeyStatusDto anthropic = key(run(), "ANTHROPIC_API_KEY");
    assertTrue(anthropic.set());
    assertEquals("...9876", anthropic.masked());
  }

  @Test
  void aBlankValueDoesNotCountAsConfigured() {
    envFile("OPENAI_API_KEY=   ");

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertFalse(openai.set());
    assertNull(openai.masked());
  }

  @Test
  void commentedAndMalformedEnvLinesAreIgnored() {
    envFile("""
        # OPENAI_API_KEY=commented-out
        this line has no equals sign
        =leading-equals-no-key
        OPENAI_API_KEY=real-value-4321
        """);

    assertEquals("...4321", key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void statusRowsFillInProvidersConfiguredOutsideTheEnvFile() {
    statusOutput("""
        ◆ API Keys
          OpenAI  ✓ configured
          DeepSeek  ✗ not configured
        """);

    assertTrue(key(run(), "OPENAI_API_KEY").set());
    assertFalse(key(run(), "DEEPSEEK_API_KEY").set());
    // the status report says nothing about the value, so nothing is masked
    assertNull(key(run(), "OPENAI_API_KEY").masked());
  }

  @Test
  void theEnvFileWinsOverTheStatusReport() {
    statusOutput("""
        ◆ API Keys
          OpenAI  ✗ not configured
        """);
    envFile("OPENAI_API_KEY=sk-real-5555");

    ApiKeyStatusDto openai = key(run(), "OPENAI_API_KEY");
    assertTrue(openai.set());
    assertEquals("...5555", openai.masked());
  }

  @Test
  void ansiColourCodesAreStrippedBeforeParsing() {
    statusOutput("[1m◆ API Keys[0m\n  [32mOpenAI[0m  ✓ configured\n");

    assertTrue(key(run(), "OPENAI_API_KEY").set());
  }

  @Test
  void authAndApiKeyProviderSectionsAreReportedSeparately() {
    statusOutput("""
        ◆ Auth Providers
          Nous Portal  ✓ signed in
          Claude  ✗ not signed in (run: hermes portal)
        ◆ API-Key Providers
          OpenRouter  ✓ key present
        """);

    AgentSetupDto dto = run();
    assertEquals(2, dto.authProviders().size());
    assertEquals("Nous Portal", dto.authProviders().getFirst().label());
    assertTrue(dto.authProviders().getFirst().ok());

    AuthProviderDto claude = dto.authProviders().get(1);
    assertFalse(claude.ok());
    assertEquals("hermes portal", claude.hint());

    assertEquals(1, dto.apiKeyProviders().size());
    assertEquals("OpenRouter", dto.apiKeyProviders().getFirst().label());
  }

  @Test
  void deeplyIndentedDetailLinesAreNotMistakenForRows() {
    statusOutput("""
        ◆ Auth Providers
          Nous Portal  ✓ signed in
              token expires in 30 days ✓
        """);

    assertEquals(1, run().authProviders().size());
  }

  @Test
  void messagingFallsBackToTheEnvWhenTheStatusReportIsSilent() {
    envFile("""
        TELEGRAM_BOT_TOKEN=bot-token
        TELEGRAM_HOME_CHANNEL=@ops
        """);

    MessagingStatusDto telegram = run().messaging().stream()
        .filter(m -> m.label().equals("Telegram")).findFirst().orElseThrow();
    assertTrue(telegram.ok());
    assertEquals("configured", telegram.status());
    assertEquals("@ops", telegram.homeChannel());
  }

  @Test
  void aFailingStatusCommandDegradesToTheEnvInsteadOfFailingTheRequest() {
    when(profiles.exec(anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("container is not running"));
    envFile("OPENAI_API_KEY=sk-still-here-7777");

    AgentSetupDto dto = run();
    assertTrue(key(dto, "OPENAI_API_KEY").set());
    assertTrue(dto.authProviders().isEmpty());
    assertFalse(dto.messaging().isEmpty());
  }

  @Test
  void aNamedProfileAsksHermesForThatProfile() {
    when(profiles.profileDir("scout")).thenReturn("/opt/data/profiles/scout");

    setup.setup(URL, CONTAINER, "scout");

    org.mockito.Mockito.verify(profiles)
        .exec(URL, CONTAINER, List.of("hermes", "-p", "scout", "status"));
  }

  @Test
  void theDefaultProfileOmitsTheProfileFlag() {
    run();

    org.mockito.Mockito.verify(profiles).exec(URL, CONTAINER, List.of("hermes", "status"));
  }

  @Test
  void theEnvTemplateDocumentsEveryKnownVariable() {
    String template = HermesSetup.envTemplate();

    for (HermesSetup.ApiKeySpec spec : HermesSetup.API_KEYS) {
      assertTrue(template.contains(spec.envVar()), "template is missing " + spec.envVar());
    }
    for (HermesSetup.MessagingSpec spec : HermesSetup.MESSAGING) {
      assertTrue(template.contains(spec.tokenVar()), "template is missing " + spec.tokenVar());
    }
    // every line is commented out, so seeding a profile with it enables nothing
    template.lines().filter(line -> !line.isBlank())
        .forEach(line -> assertTrue(line.startsWith("#"), "uncommented template line: " + line));
  }
}
