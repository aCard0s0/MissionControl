package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Writing a variable into a profile's {@code .env}.
 *
 * <p>The value becomes a whole line in that file, so what is allowed into it decides
 * whether one write can add variables the caller never asked for. The captured argv also
 * documents the shell-safety contract: the script is fixed text, and every caller-supplied
 * string is a positional argument.
 */
class HermesEnvWriteTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "scout";
  private static final String ENV_PATH = "/opt/data/profiles/scout/.env";

  private DockerExecService dockerExec;
  private HermesEnvFile envFile;
  private HermesSetup setup;

  @BeforeEach
  void setUp() {
    dockerExec = mock(DockerExecService.class);
    envFile = AgentsWiring.envFile(dockerExec);
    setup = new HermesSetup(AgentsWiring.files(dockerExec), envFile);
    // every exec succeeds; the reporting read-back after a write returns an empty .env
    when(dockerExec.runAsUser(any(), anyString(), any(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));
  }

  private ArgumentCaptor<List<String>> captureArgv() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    return argv;
  }

  @Test
  void anEnvValueContainingANewlineIsRejectedBeforeItReachesTheContainer() {
    // "a\nEVIL=1" would append a second .env line. removeEnvVar deletes by matching
    // "^KEY=", so the injected line is unreachable and permanent.
    IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class, () ->
        setup.putEnv(HOST, CONTAINER, PROFILE,
            List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\nANTHROPIC_API_KEY=sk-attacker"))));

    assertTrue(rejected.getMessage().contains("OPENAI_API_KEY"));
    verifyNoInteractions(dockerExec);
  }

  @Test
  void anEnvValueContainingACarriageReturnOrNulIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\rEVIL=1"))));
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\0truncated"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void anInvalidKeyAnywhereInTheBatchBlocksEveryWrite() {
    // the bad entry is second: validation is a full pass over the batch before any write,
    // so a partially applied batch is not possible
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-fine"), new EnvEntry("lowercase", "x"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void aBadValueAnywhereInTheBatchAlsoBlocksEveryWrite() {
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(HOST, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-fine"), new EnvEntry("XAI_API_KEY", "a\nb"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void theRuleHoldsAtTheWriterSoNoCallerCanReachTheFileWithoutIt() {
    // putEnv is not the only way into this file: HermesModelConfig writes a provider key
    // straight off a create-agent request, which carries no pattern of its own. While the
    // rule lived in putEnv, "sk-real\nANTHROPIC_API_KEY=sk-attacker" in that field wrote a
    // second, undeletable .env line.
    assertThrows(IllegalArgumentException.class, () ->
        envFile.write(HOST, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-real\nXAI_API_KEY=sk-other"));
    assertThrows(IllegalArgumentException.class, () ->
        envFile.write(HOST, CONTAINER, PROFILE, "lowercase", "value"));
    assertThrows(IllegalArgumentException.class, () ->
        envFile.remove(HOST, CONTAINER, PROFILE, "OPENAI_API_KEY; rm -rf /"));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void theKeyAndValueAreAlwaysPositionalArgumentsAndNeverInterpolatedIntoTheScript() {
    envFile.write(HOST, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-secret-value");

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec).runAsUser(any(), anyString(), any(), argv.capture(),
        org.mockito.ArgumentMatchers.eq("write profile environment"),
        anyBoolean(), anyBoolean(), any(Duration.class));

    List<String> command = argv.getValue();
    assertEquals("sh", command.get(0));
    assertEquals("-lc", command.get(1));
    // the script is fixed text; the path, key and value follow as $1/$2/$3
    String script = command.get(2);
    assertTrue(script.contains("$1") && script.contains("$2") && script.contains("$3"));
    assertTrue(!script.contains("OPENAI_API_KEY") && !script.contains("sk-secret-value"),
        "caller input was interpolated into the script body");
    assertEquals(List.of(ENV_PATH, "OPENAI_API_KEY", "sk-secret-value"),
        command.subList(4, command.size()));
  }

  @Test
  void writingAnEnvVarUsesTheSensitiveExecSoTheValueNeverReachesALog() {
    envFile.write(HOST, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-secret-value");

    // sensitive=true keeps argv and command output out of the failure message and the log
    verify(dockerExec).runAsUser(eqHost(), eqContainer(), any(), any(),
        org.mockito.ArgumentMatchers.eq("write profile environment"),
        org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true),
        any(Duration.class));
  }

  @Test
  void aBlankValueRemovesTheVariableRatherThanWritingAnEmptyOne() {
    setup.putEnv(HOST, CONTAINER, PROFILE, List.of(new EnvEntry("OPENAI_API_KEY", "   ")));

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec, org.mockito.Mockito.atLeastOnce()).runAsUser(any(), anyString(), any(),
        argv.capture(), anyString(), anyBoolean(), anyBoolean(), any(Duration.class));

    // the removal script takes only the path and the key — a write would carry a value too
    boolean removed = argv.getAllValues().stream().anyMatch(command ->
        command.size() == 6 && ENV_PATH.equals(command.get(4)) && "OPENAI_API_KEY".equals(command.get(5)));
    assertTrue(removed, "a blank value wrote an empty variable instead of removing it");
  }

  @Test
  void aNullEntryListIsAcceptedAndWritesNothing() {
    setup.putEnv(HOST, CONTAINER, PROFILE, null);

    // only the read-back that builds the response, no writes
    verify(dockerExec, org.mockito.Mockito.never()).runAsUser(any(), anyString(), any(), any(),
        org.mockito.ArgumentMatchers.eq("write profile environment"), anyBoolean(), anyBoolean(),
        any(Duration.class));
  }

  private static DockerHostRef eqHost() {
    return org.mockito.ArgumentMatchers.eq(HOST);
  }

  private static String eqContainer() {
    return org.mockito.ArgumentMatchers.eq(CONTAINER);
  }

  // ── masking a stored key for display ──────────────────────────────────────

  @Test
  void aStoredKeyIsShownOnlyAsItsLastFewCharacters() {
    String env = "OTHER=x\nANTHROPIC_API_KEY=sk-ant-api03-abcdefghij\n";

    String masked = HermesEnvFile.maskApiKey(env, "anthropic");

    assertEquals("...ghij", masked);
    assertTrue(!masked.contains("sk-ant"), "no prefix of the key may reach the client");
  }

  @Test
  void aVeryShortStoredValueIsMaskedEntirelyRatherThanRevealed() {
    assertEquals("...", HermesEnvFile.maskApiKey("ANTHROPIC_API_KEY=abc\n", "anthropic"));
  }

  @Test
  void aProviderWithNoKeyInTheFileMasksToNothing() {
    assertEquals("", HermesEnvFile.maskApiKey("OPENAI_API_KEY=sk-openai-abcdefgh\n", "anthropic"));
    assertEquals("", HermesEnvFile.maskApiKey("ANTHROPIC_API_KEY=\n", "anthropic"));
  }

  @Test
  void anEmptyFileOrAKeylessProviderMasksToNothing() {
    assertEquals("", HermesEnvFile.maskApiKey(null, "anthropic"));
    assertEquals("", HermesEnvFile.maskApiKey("   ", "anthropic"));
    // a provider the registry does not know has no env var to look for
    assertEquals("", HermesEnvFile.maskApiKey("ANTHROPIC_API_KEY=sk-ant-abcdefgh\n", "who-knows"));
    assertEquals("", HermesEnvFile.maskApiKey("ANTHROPIC_API_KEY=sk-ant-abcdefgh\n", null));
  }

  @Test
  void theProviderNameIsNormalisedTheSameWayItIsElsewhere() {
    // 'nous-portal' and 'Nous' are the same provider to the registry
    String env = "NOUS_API_KEY=sk-nous-abcdefgh\n";

    assertEquals(HermesEnvFile.maskApiKey(env, "nous"), HermesEnvFile.maskApiKey(env, " Nous-Portal "));
  }

  @Test
  void aKeyThatOnlyAppearsAsASubstringOfAnotherVariableIsNotRead() {
    // MY_ANTHROPIC_API_KEY is a different variable
    assertEquals("", HermesEnvFile.maskApiKey("MY_ANTHROPIC_API_KEY=sk-ant-abcdefgh\n", "anthropic"));
  }

  // ── batch validation ─────────────────────────────────────────────────────

  @Test
  void aNullEntryInTheBatchBlocksEveryWrite() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> setup.putEnv(HOST, CONTAINER, PROFILE,
            java.util.Arrays.asList(new EnvEntry("GOOD_KEY", "value"), null)));

    assertEquals("invalid env key: null", failure.getMessage());
    verifyNoInteractions(dockerExec);
  }

  @Test
  void anEntryWithNoKeyAtAllBlocksEveryWrite() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> setup.putEnv(HOST, CONTAINER, PROFILE, List.of(new EnvEntry(null, "value"))));

    assertEquals("invalid env key: null", failure.getMessage());
    verifyNoInteractions(dockerExec);
  }

  // ── seeding the template ─────────────────────────────────────────────────

  @Test
  void anExistingEnvIsNeverOverwrittenByTheTemplate() {
    // every exec in this harness succeeds, so the .env reads as present — and seeding it again
    // would delete every key the operator had configured
    envFile.seedIfMissing(HOST, CONTAINER, PROFILE);

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec, org.mockito.Mockito.atLeastOnce()).runAsUser(any(), anyString(), any(),
        argv.capture(), anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
    assertTrue(argv.getAllValues().stream().noneMatch(command -> command.contains("printf")));
  }

  @Test
  void anAbsentEnvIsSeededWithTheCommentedTemplate() {
    // 'test -f' fails, so the file is not there yet
    when(dockerExec.runAsUser(any(), anyString(), any(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenAnswer(invocation -> {
          List<String> command = invocation.getArgument(3);
          boolean probing = command.stream().anyMatch(arg -> arg != null && arg.startsWith("test -f"));
          return new DockerExecService.ExecResult(probing ? 1 : 0, "", "");
        });

    envFile.seedIfMissing(HOST, CONTAINER, PROFILE);

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec, org.mockito.Mockito.atLeastOnce()).runAsUser(any(), anyString(), any(),
        argv.capture(), anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
    List<String> write = argv.getAllValues().stream()
        .filter(command -> command.stream().anyMatch(arg -> arg != null && arg.contains("printf")))
        .findFirst().orElseThrow(() -> new AssertionError("nothing was written"));
    // every key is written commented out, so the file documents itself without enabling anything
    assertTrue(write.contains(ENV_PATH), write.toString());
    assertTrue(write.stream().anyMatch(arg -> arg != null && arg.contains("# ANTHROPIC_API_KEY=")),
        "the documented template is written");
  }
}
