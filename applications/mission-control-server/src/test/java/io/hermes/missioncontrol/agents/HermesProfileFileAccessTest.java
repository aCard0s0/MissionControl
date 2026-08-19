package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * How profile files are reached inside the container.
 *
 * <p>Two of these matter beyond correctness. The profile name arrives as a URL path
 * segment and is concatenated into a container path, so its validation is a path guard.
 * And every write runs {@code mkdir -p} on the parent — which a skill subdirectory needs,
 * but which also meant a write against a name that had no profile silently created one.
 */
class HermesProfileFileAccessTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");
  private static final String CONTAINER = "c1";

  private DockerExecService dockerExec;
  private HermesContainerFiles files;
  private HermesEnvFile envFile;
  private HermesProfiles profiles;

  @BeforeEach
  void setUp() {
    dockerExec = mock(DockerExecService.class);
    files = AgentsWiring.files(dockerExec);
    envFile = AgentsWiring.envFile(dockerExec);
    profiles = AgentsWiring.profiles(dockerExec);
  }

  /** Every exec succeeds; {@code test -d} therefore reports the directory as present. */
  private void theProfileExists() {
    when(dockerExec.runAsUser(any(), anyString(), any(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, "", ""));
  }

  /** {@code test -d} reports a missing directory as a non-zero exit. */
  private void theProfileDoesNotExist() {
    when(dockerExec.runAsUser(any(), anyString(), any(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(1, "", ""));
  }

  private ArgumentCaptor<List<String>> captureArgv() {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
    return argv;
  }

  @Test
  void writingASoulToAProfileThatDoesNotExistDoesNotCreateOne() {
    theProfileDoesNotExist();

    // a mistyped name in PUT /api/agents/{host}/{container}/{name}/soul used to mint
    // /opt/data/profiles/<typo>/ and the phantom profile then showed up in list()
    assertThrows(NoSuchElementException.class,
        () -> profiles.updateSoul(HOST, CONTAINER, "tpyo", "you are a helpful agent"));

    // the existence probe runs, but nothing carrying the content does — asserted on argv
    // because the probe and the write share the same operation label
    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec, org.mockito.Mockito.atLeastOnce()).runAsUser(any(), anyString(), any(),
        argv.capture(), anyString(), anyBoolean(), anyBoolean(), any(Duration.class));
    assertTrue(
        argv.getAllValues().stream().flatMap(List::stream)
            .noneMatch(arg -> arg.contains("you are a helpful agent")),
        "the soul was written to a profile that does not exist");
  }

  @Test
  void writingAMemoryOrAConfigToAProfileThatDoesNotExistIsAlsoRefused() {
    theProfileDoesNotExist();

    assertThrows(NoSuchElementException.class,
        () -> profiles.updateMemory(HOST, CONTAINER, "tpyo", "remembered"));
    assertThrows(NoSuchElementException.class,
        () -> profiles.updateConfig(HOST, CONTAINER, "tpyo", "model:\n  provider: anthropic\n"));
  }

  @Test
  void addingAnMcpServerToAProfileThatDoesNotExistIsRefused() {
    theProfileDoesNotExist();

    assertThrows(NoSuchElementException.class, () -> profiles.addMcpServer(HOST, CONTAINER, "tpyo",
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null)));

    // the atomic-write path mkdir -p's too, so it needed the same guard
    verify(dockerExec, never()).runAsUser(any(), anyString(), any(), any(),
        eq("write MCP configuration"), anyBoolean(), anyBoolean(), any(Duration.class));
  }

  @Test
  void writingAnEnvVarToAProfileThatDoesNotExistIsRefused() {
    theProfileDoesNotExist();

    assertThrows(NoSuchElementException.class,
        () -> envFile.write(HOST, CONTAINER, "tpyo", "OPENAI_API_KEY", "sk-x"));

    verify(dockerExec, never()).runAsUser(any(), anyString(), any(), any(),
        eq("write profile environment"), anyBoolean(), anyBoolean(), any(Duration.class));
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirectoryIsRejected() {
    // the name is a URL path segment concatenated into a container path
    assertThrows(IllegalArgumentException.class, () -> ProfilePaths.profileDir("../../etc"));
    assertThrows(IllegalArgumentException.class, () -> ProfilePaths.profileDir("a/b"));
    assertThrows(IllegalArgumentException.class, () -> ProfilePaths.profileDir(""));
    assertThrows(IllegalArgumentException.class, () -> ProfilePaths.profileDir(null));
    assertThrows(IllegalArgumentException.class, () -> ProfilePaths.profileDir(".hidden"));
  }

  @Test
  void theDefaultProfileMapsToHermesHomeRatherThanTheProfilesDirectory() {
    assertEquals("/opt/data", ProfilePaths.profileDir("default"));
    assertEquals("/opt/data/profiles/scout", ProfilePaths.profileDir("scout"));
  }

  @Test
  void readFileAndWriteFilePassThePathAsAPositionalArgument() {
    theProfileExists();

    files.readFile(HOST, CONTAINER, "/opt/data/profiles/scout/SOUL.md");

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec).runAsUser(any(), anyString(), any(), argv.capture(), anyString(),
        anyBoolean(), anyBoolean(), any(Duration.class));

    List<String> command = argv.getValue();
    assertEquals(List.of("sh", "-lc"), command.subList(0, 2));
    // the path is $1, never spliced into the script text
    assertTrue(command.get(2).contains("$1"));
    assertEquals("/opt/data/profiles/scout/SOUL.md", command.getLast());
  }

  @Test
  void writingAConfigUsesAnAtomicRenameWithATrapCleanup() {
    theProfileExists();
    // the config read-back returns an empty document, which the editor treats as a new one
    profiles.addMcpServer(HOST, CONTAINER, "scout",
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null));

    ArgumentCaptor<List<String>> argv = captureArgv();
    verify(dockerExec).runAsUser(any(), anyString(), any(), argv.capture(),
        eq("write MCP configuration"), anyBoolean(), anyBoolean(), any(Duration.class));

    String script = argv.getValue().get(2);
    // a reader must see either the old config or the new one, never a half-written YAML
    assertTrue(script.contains("mv -f"), "the config write is not an atomic rename");
    assertTrue(script.contains("trap"), "a failed write leaves its temp file behind");
  }

  @Test
  void aFullConfigWriteIsSensitiveBecauseItMayCarryAuthenticationHeaders() {
    theProfileExists();

    profiles.addMcpServer(HOST, CONTAINER, "scout",
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null,
            java.util.Map.of("Authorization", "Bearer secret-token")));

    // sensitive=true keeps the argv — and therefore the header — out of any error or log
    verify(dockerExec).runAsUser(any(), anyString(), any(), any(),
        eq("write MCP configuration"), eq(true), eq(true), any(Duration.class));
  }
}
