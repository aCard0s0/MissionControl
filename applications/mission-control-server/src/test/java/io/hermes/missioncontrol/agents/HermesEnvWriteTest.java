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

  private static final String URL = "unix:///var/run/docker.sock";
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
    when(dockerExec.runAsUser(anyString(), anyString(), any(), any(), anyString(), anyBoolean(),
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
        setup.putEnv(URL, CONTAINER, PROFILE,
            List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\nANTHROPIC_API_KEY=sk-attacker"))));

    assertTrue(rejected.getMessage().contains("OPENAI_API_KEY"));
    verifyNoInteractions(dockerExec);
  }

  @Test
  void anEnvValueContainingACarriageReturnOrNulIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(URL, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\rEVIL=1"))));
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(URL, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-real\0truncated"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void anInvalidKeyAnywhereInTheBatchBlocksEveryWrite() {
    // the bad entry is second: validation is a full pass over the batch before any write,
    // so a partially applied batch is not possible
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(URL, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-fine"), new EnvEntry("lowercase", "x"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void aBadValueAnywhereInTheBatchAlsoBlocksEveryWrite() {
    assertThrows(IllegalArgumentException.class, () -> setup.putEnv(URL, CONTAINER, PROFILE,
        List.of(new EnvEntry("OPENAI_API_KEY", "sk-fine"), new EnvEntry("XAI_API_KEY", "a\nb"))));

    verifyNoInteractions(dockerExec);
  }

  @Test
  void theKeyAndValueAreAlwaysPositionalArgumentsAndNeverInterpolatedIntoTheScript() {
    envFile.write(URL, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-secret-value");

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec).runAsUser(anyString(), anyString(), any(), argv.capture(),
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
    envFile.write(URL, CONTAINER, PROFILE, "OPENAI_API_KEY", "sk-secret-value");

    // sensitive=true keeps argv and command output out of the failure message and the log
    verify(dockerExec).runAsUser(eqUrl(), eqContainer(), any(), any(),
        org.mockito.ArgumentMatchers.eq("write profile environment"),
        org.mockito.ArgumentMatchers.eq(true), org.mockito.ArgumentMatchers.eq(true),
        any(Duration.class));
  }

  @Test
  void aBlankValueRemovesTheVariableRatherThanWritingAnEmptyOne() {
    setup.putEnv(URL, CONTAINER, PROFILE, List.of(new EnvEntry("OPENAI_API_KEY", "   ")));

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec, org.mockito.Mockito.atLeastOnce()).runAsUser(anyString(), anyString(), any(),
        argv.capture(), anyString(), anyBoolean(), anyBoolean(), any(Duration.class));

    // the removal script takes only the path and the key — a write would carry a value too
    boolean removed = argv.getAllValues().stream().anyMatch(command ->
        command.size() == 6 && ENV_PATH.equals(command.get(4)) && "OPENAI_API_KEY".equals(command.get(5)));
    assertTrue(removed, "a blank value wrote an empty variable instead of removing it");
  }

  @Test
  void aNullEntryListIsAcceptedAndWritesNothing() {
    setup.putEnv(URL, CONTAINER, PROFILE, null);

    // only the read-back that builds the response, no writes
    verify(dockerExec, org.mockito.Mockito.never()).runAsUser(anyString(), anyString(), any(), any(),
        org.mockito.ArgumentMatchers.eq("write profile environment"), anyBoolean(), anyBoolean(),
        any(Duration.class));
  }

  private static String eqUrl() {
    return org.mockito.ArgumentMatchers.eq(URL);
  }

  private static String eqContainer() {
    return org.mockito.ArgumentMatchers.eq(CONTAINER);
  }
}
