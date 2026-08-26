package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Every read and write this package makes inside a Hermes container: the exec seam
 * and the small shell scripts that back it.
 *
 * <p>Split out of {@link HermesProfiles} so the shell quoting and the
 * argv-never-interpolated rule live in one place. Nothing here knows what a
 * profile, a skill or an MCP entry is — callers hand it absolute container paths.
 */
@Component
class HermesContainerFiles {

  private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(30);

  private final DockerExecService dockerExec;

  /**
   * One lock per profile, for the edits that read a file, change it here, and write it back.
   *
   * <p>{@link #writeFileAtomically} makes a <em>reader</em> see one version or the other. It says
   * nothing about two writers: five paths read {@code config.yaml} into the JVM and write it back
   * — the MCP entries, the webhooks, the skill toggles, the config editor and the model settings
   * — and without this the later write silently discards whatever the earlier one added, with
   * both requests reporting success. The one that made this worth finding: the Agent layer
   * disables an MCP entry, verifies it, drops the link row, and a skill toggle that had read the
   * file first then puts the entry back — leaving an enabled entry pointing at a catalog server
   * that no longer exists, and no link row saying where it came from.
   *
   * <p>Reentrant because these paths nest: a create writes the model config inside a flow that
   * already holds the profile.
   *
   * <p>Bounded by the number of profiles this dashboard has touched, which is the same order as
   * the containers it manages.
   *
   * <p>ponytail: one JVM. A second dashboard against the same daemon, or an edit made by
   * {@code hermes} inside the container, is outside this lock — that needs a version check on the
   * file itself (write only if the content still hashes to what was read), which is worth doing
   * the day a second writer exists.
   */
  private final Map<String, ReentrantLock> profileLocks = new ConcurrentHashMap<>();

  HermesContainerFiles(DockerExecService dockerExec) {
    this.dockerExec = dockerExec;
  }

  /**
   * Runs a profile's read-modify-write with no other one interleaving.
   *
   * <p>Held here rather than in each of the five callers because this class is already the single
   * seam they all reach the container through, and a lock per caller is not a lock.
   */
  <T> T serialized(String containerId, String profileName, Supplier<T> work) {
    ReentrantLock lock = profileLocks.computeIfAbsent(
        containerId + "/" + profileName, ignored -> new ReentrantLock());
    lock.lock();
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }

  /** {@link #serialized} for an edit with nothing to hand back. */
  void serialized(String containerId, String profileName, Runnable work) {
    serialized(containerId, profileName, () -> {
      work.run();
      return null;
    });
  }

  ExecResult exec(DockerHostRef host, String containerId, List<String> command) {
    return exec(host, containerId, command, true);
  }

  /**
   * check=false callers (e.g. dirExists) interpret the exit code themselves — and an exit
   * code of {@link DockerExecService#EXIT_STATUS_UNAVAILABLE} means the daemon never
   * reported one, so "did the command succeed" has no answer.
   */
  ExecResult exec(DockerHostRef host, String containerId, List<String> command, boolean check) {
    return dockerExec.runAsUser(
        host, containerId, "hermes", command, "Hermes command", check, false, EXEC_TIMEOUT);
  }

  /** As {@link #exec}, but argv is kept out of Docker execution errors and logs. */
  ExecResult execSensitive(
      DockerHostRef host, String containerId, List<String> command, String operation) {
    return dockerExec.runAsUser(
        host, containerId, "hermes", command, operation, true, true, EXEC_TIMEOUT);
  }

  /**
   * KNOWN GAP: {@code cat … || true} cannot tell an empty file from an absent one, so a
   * read of a profile that does not exist yields an empty profile rather than a 404. Most
   * callers rely on that tolerance (a profile legitimately has no MEMORY.md, no .env, no
   * gateway_state.json), so separating the two needs a per-call decision rather than a
   * change here. The write paths are guarded by {@link #requireProfileDir} instead, which
   * is what stopped a mistyped name from creating a profile.
   */
  String readFile(DockerHostRef host, String containerId, String path) {
    ExecResult result =
        exec(host, containerId, List.of("sh", "-lc", "cat \"$1\" 2>/dev/null || true", "_", path));
    return result.stdout();
  }

  /**
   * {@link #readFile} for several paths at once, in one exec.
   *
   * <p>The agents listing is the reason. It reads four documents per profile and one SKILL.md
   * per skill, inside a loop over every profile in the container, on a twelve-second poll: a
   * container with three profiles and the bundled skill set was spending around ninety-six exec
   * round trips per poll, each one creating and inspecting an exec inside the container, on the
   * same daemon the ten-second fleet poll and the three-second stats poll are using.
   *
   * <p>Framed with a marker carrying a nonce minted for this call, so a file cannot contain its
   * own delimiter — SKILL.md and MEMORY.md are written by agents, and a fixed sentinel would be
   * a matter of time. Paths travel as argv, never interpolated into the script, which is the
   * rule this class exists to hold.
   *
   * <p>Absent files answer empty, exactly as {@link #readFile} does and for the same reason: a
   * profile legitimately has no MEMORY.md, no .env and no gateway_state.json.
   */
  Map<String, String> readFiles(DockerHostRef host, String containerId, List<String> paths) {
    Map<String, String> contents = new LinkedHashMap<>();
    if (paths.isEmpty()) return contents;
    for (String path : paths) contents.put(path, "");

    String marker = "==mission-control-" + UUID.randomUUID() + "==";
    List<String> command = new ArrayList<>(List.of("sh", "-lc",
        "marker=\"$1\"; shift;"
            + " for f in \"$@\"; do printf '%s%s\\n' \"$marker\" \"$f\";"
            + " cat \"$f\" 2>/dev/null || true; done",
        "_", marker));
    command.addAll(paths);

    String stdout = exec(host, containerId, command).stdout();
    for (String chunk : stdout.split(Pattern.quote(marker))) {
      int newline = chunk.indexOf('\n');
      if (newline < 0) continue;   // the empty head before the first marker
      String path = chunk.substring(0, newline);
      // only what was asked for: `ls`-style surprises in the output cannot invent a key
      if (contents.containsKey(path)) contents.put(path, chunk.substring(newline + 1));
    }
    return contents;
  }

  void writeFile(DockerHostRef host, String containerId, String path, String content) {
    String script = String.join(" ",
        "path=\"$1\"; content=\"$2\";",
        "mkdir -p \"$(dirname \"$path\")\";",
        "printf '%s' \"$content\" > \"$path\";");
    exec(host, containerId, List.of("sh", "-lc", script, "_", path, content));
  }

  /**
   * Writes a complete config through a sibling temp file and atomic rename, so
   * readers can observe either the old definition or the new one, never the
   * delete half of a rename or a partially-written YAML document.
   */
  void writeFileAtomically(DockerHostRef host, String containerId, String path, String content) {
    String script = String.join(" ",
        "path=\"$1\"; content=\"$2\";",
        "mkdir -p \"$(dirname \"$path\")\";",
        "tmp=\"${path}.mission-control.$$\";",
        "trap 'rm -f \"$tmp\"' 0 1 2 15;",
        "printf '%s' \"$content\" > \"$tmp\";",
        "mv -f \"$tmp\" \"$path\";",
        "trap - 0 1 2 15;");
    // The complete YAML may carry authentication headers, so do not include
    // argv in Docker execution errors/logs.
    execSensitive(
        host, containerId, List.of("sh", "-lc", script, "_", path, content),
        "write MCP configuration");
  }

  void removeTree(DockerHostRef host, String containerId, String path) {
    exec(host, containerId, List.of("sh", "-lc", "rm -rf \"$1\"", "_", path));
  }

  boolean dirExists(DockerHostRef host, String containerId, String path) {
    return exec(host, containerId, List.of("sh", "-lc", "test -d \"$1\"", "_", path), false)
        .exitCode() == 0;
  }

  boolean fileExists(DockerHostRef host, String containerId, String path) {
    return exec(host, containerId, List.of("sh", "-lc", "test -f \"$1\"", "_", path), false)
        .exitCode() == 0;
  }

  /**
   * The profile's directory, or a 404 when there is no such profile.
   *
   * <p>{@link #writeFile} runs {@code mkdir -p} on the parent, which a skill subdirectory
   * needs. Without this guard that also means a PUT against a mistyped profile name mints
   * the directory, and the phantom profile then appears in the agents list — indistinguishable
   * from a real one, and holding only whatever the typo'd request wrote.
   */
  String requireProfileDir(DockerHostRef host, String containerId, String name) {
    String dir = ProfilePaths.profileDir(name);
    if (!dirExists(host, containerId, dir)) {
      throw new NoSuchElementException("unknown agent profile: " + name);
    }
    return dir;
  }

  /** Non-empty trimmed lines of a command's stdout — the shape every {@code ls}/{@code find} here returns. */
  static List<String> lines(String stdout) {
    return (stdout == null ? "" : stdout).lines()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .toList();
  }
}
