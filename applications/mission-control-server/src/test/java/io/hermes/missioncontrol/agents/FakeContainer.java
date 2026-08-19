package io.hermes.missioncontrol.agents;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A scriptable stand-in for the files inside a Hermes container.
 *
 * <p>Every collaborator in this package reaches the container through one seam — a shell
 * command as argv — so a fake that answers by inspecting that argv can serve all of them.
 * A test declares the filesystem it wants ({@code file}, {@code dir}) and a python or
 * {@code hermes} response ({@code onCommand}), rather than stubbing exec call by call in
 * order, which breaks the moment a collaborator adds a read.
 */
final class FakeContainer {

  static final DockerHostRef HOST = new DockerHostRef("dh-test", "unix:///var/run/docker.sock");
  static final String CONTAINER = "c1";

  private final Map<String, String> files = new LinkedHashMap<>();
  private final List<String> dirs = new ArrayList<>();
  private final Map<String, ExecResult> commands = new LinkedHashMap<>();
  private final List<List<String>> executed = new ArrayList<>();
  private final DockerExecService dockerExec = mock(DockerExecService.class);

  FakeContainer() {
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(),
        anyBoolean(), anyBoolean(), any(Duration.class)))
        .thenAnswer(invocation -> answer(invocation.getArgument(3)));
  }

  /** A file present with this content. Its parent directories exist implicitly. */
  FakeContainer file(String path, String content) {
    files.put(path, content);
    int slash = path.lastIndexOf('/');
    if (slash > 0) dir(path.substring(0, slash));
    return this;
  }

  FakeContainer dir(String path) {
    if (!dirs.contains(path)) dirs.add(path);
    return this;
  }

  /**
   * Response for the first command whose script or arguments contain {@code marker} —
   * a {@code find}, a {@code python3 -c}, or a {@code hermes} sub-command.
   */
  FakeContainer onCommand(String marker, String stdout) {
    return onCommand(marker, new ExecResult(0, stdout, ""));
  }

  FakeContainer onCommand(String marker, ExecResult result) {
    commands.put(marker, result);
    return this;
  }

  HermesContainerFiles files() {
    return new HermesContainerFiles(dockerExec);
  }

  DockerExecService dockerExec() {
    return dockerExec;
  }

  /** Every argv the collaborator under test ran, in order. */
  List<List<String>> executed() {
    return List.copyOf(executed);
  }

  private ExecResult answer(List<String> command) {
    executed.add(List.copyOf(command));
    String script = command.size() > 2 ? command.get(2) : "";
    String path = command.getLast();

    for (Map.Entry<String, ExecResult> scripted : commands.entrySet()) {
      if (command.stream().anyMatch(arg -> arg != null && arg.contains(scripted.getKey()))) {
        return scripted.getValue();
      }
    }
    if (script.startsWith("test -f")) {
      return new ExecResult(files.containsKey(path) ? 0 : 1, "", "");
    }
    if (script.startsWith("test -d")) {
      return new ExecResult(dirs.contains(path) ? 0 : 1, "", "");
    }
    if (script.startsWith("cat ")) {
      // cat … || true cannot tell an absent file from an empty one
      return new ExecResult(0, files.getOrDefault(path, ""), "");
    }
    return new ExecResult(0, "", "");
  }
}
