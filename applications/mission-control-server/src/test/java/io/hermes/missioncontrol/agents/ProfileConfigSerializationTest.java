package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Two edits of one profile's {@code config.yaml} landing at the same time.
 *
 * <p>Five paths read this file, change one key in the JVM, and write it back — the MCP entries,
 * the webhooks, the skill toggles, the config editor and the model settings. The read and the
 * write are separate {@code docker exec}s, so without a lock the later writer holds a copy of
 * the file from before the earlier one touched it, and puts that copy back: the first edit is
 * gone and both requests reported success.
 *
 * <p>The interleaving that made this worth finding is not two operators racing. It is
 * {@code AgentMcpCatalogService.disableAndUnlinkForDeletion}, which walks every Agent holding a
 * catalog server disabling its entry, verifying the entry is disabled, and then dropping the
 * link row — while the agents page polls every twelve seconds and an operator is free to click
 * anything. A skill toggle that read the file first re-enables the entry after the link row is
 * already gone, leaving an enabled MCP entry pointing at a catalog server that no longer exists
 * and nothing recording where it came from.
 *
 * <p>Driven through the two real collaborators rather than through {@code serialized} directly:
 * the lock is only worth anything if separate collaborators sharing one seam contend on it, and
 * a test that called the lock itself would pass with each of them holding a private one.
 */
class ProfileConfigSerializationTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");
  private static final String CONTAINER = "c1";
  private static final String PROFILE = "default";
  private static final String CONFIG = ProfilePaths.profileDir(PROFILE) + "/config.yaml";

  /**
   * A container whose files actually round-trip, so a write that discards another write is
   * observable. {@link FakeContainer} answers reads from a fixed map and drops writes, which
   * cannot show a lost update.
   */
  private static final class MutableContainer {

    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final DockerExecService dockerExec = mock(DockerExecService.class);
    /** Blocks {@code holder}'s read of config.yaml until the test lets it through. */
    private volatile CountDownLatch release;
    private volatile String holder;
    /** Counted down once the holder is actually inside its read. */
    private final CountDownLatch holding = new CountDownLatch(1);
    /** Every read and write, in the order the container saw them. */
    private final List<String> operations = Collections.synchronizedList(new ArrayList<>());

    MutableContainer() {
      when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(),
          anyBoolean(), anyBoolean(), any(Duration.class)))
          .thenAnswer(call -> answer(call.getArgument(3)));
    }

    MutableContainer file(String path, String content) {
      files.put(path, content);
      return this;
    }

    String read(String path) {
      return files.getOrDefault(path, "");
    }

    private ExecResult answer(List<String> command) throws InterruptedException {
      String script = command.size() > 2 ? command.get(2) : "";
      if (script.startsWith("test -d") || script.startsWith("test -f")) {
        // every path under the profile dir exists, so requireProfileDir lets the edit through
        return new ExecResult(0, "", "");
      }
      if (script.startsWith("cat ")) {
        String path = command.getLast();
        if (CONFIG.equals(path)) {
          CountDownLatch pause = release;
          if (pause != null && Thread.currentThread().getName().equals(holder)) {
            holding.countDown();
            pause.await(5, TimeUnit.SECONDS);
          }
          operations.add(Thread.currentThread().getName() + ":read");
        }
        return new ExecResult(0, read(path), "");
      }
      if (script.startsWith("marker=")) {
        String marker = command.get(4);
        StringBuilder out = new StringBuilder();
        for (String each : command.subList(5, command.size())) {
          out.append(marker).append(each).append('\n').append(read(each));
        }
        operations.add(Thread.currentThread().getName() + ":batch");
        return new ExecResult(0, out.toString(), "");
      }
      if (script.contains("mv -f") || script.contains("> \"$path\"")) {
        // the write scripts take (…, "_", path, content)
        String path = command.get(command.size() - 2);
        if (CONFIG.equals(path)) operations.add(Thread.currentThread().getName() + ":write");
        files.put(path, command.getLast());
        return new ExecResult(0, "", "");
      }
      return new ExecResult(0, "", "");
    }
  }

  @Test
  void anMcpEditAndASkillToggleOnOneProfileBothSurvive() throws Exception {
    MutableContainer container = new MutableContainer().file(CONFIG, """
        mcp_servers:
          github:
            transport: http
            url: http://mcp-github:1100/mcp
            enabled: true
        """);
    HermesContainerFiles files = new HermesContainerFiles(container.dockerExec);
    HermesProfileMcp mcp = new HermesProfileMcp(files, new HermesConfigEditor());
    HermesSkills skills = new HermesSkills(files, new HermesConfigEditor());

    // the MCP edit is held inside its read, with its write still to come
    CountDownLatch release = new CountDownLatch(1);
    container.holder = "mcp-edit";
    container.release = release;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread disabling = new Thread(
        () -> mcp.setEnabled(HOST, CONTAINER, PROFILE, "github", false), "mcp-edit");
    disabling.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    disabling.start();
    assertTrue(container.holding.await(5, TimeUnit.SECONDS), "the MCP edit never reached its read");

    // the skill toggle arrives while the MCP edit holds the profile, and has to wait for it
    Thread toggling = new Thread(
        () -> skills.setEnabled(HOST, CONTAINER, PROFILE, "pdf", false), "skill-toggle");
    toggling.setUncaughtExceptionHandler((t, e) -> failure.set(e));
    toggling.start();
    awaitWaitingOnTheProfileLock(toggling);

    release.countDown();
    disabling.join(5_000);
    toggling.join(5_000);
    if (failure.get() != null) throw new AssertionError(failure.get());

    // the toggle read the file only after the MCP edit had written it. Without that, it holds a
    // copy from before and puts it back — re-enabling an entry the Agent layer has already
    // unlinked from the catalog, with nothing left recording where it came from
    assertEquals(
        List.of("mcp-edit:read", "mcp-edit:write", "skill-toggle:read", "skill-toggle:write"),
        container.operations);

    String config = container.read(CONFIG);
    assertTrue(config.contains("enabled: false"), "the MCP edit survived: " + config);
    assertTrue(config.contains("pdf"), "the skill toggle survived: " + config);
  }

  @Test
  void twoProfilesInOneContainerDoNotWaitOnEachOther() throws Exception {
    // one lock for the whole container would serialize every agent behind the slowest edit,
    // and these files have nothing to do with each other
    MutableContainer container = new MutableContainer()
        .file(CONFIG, "mcp_servers: {}\n")
        .file(ProfilePaths.profileDir("ops") + "/config.yaml", "mcp_servers: {}\n");
    HermesContainerFiles files = new HermesContainerFiles(container.dockerExec);
    HermesSkills skills = new HermesSkills(files, new HermesConfigEditor());

    CountDownLatch release = new CountDownLatch(1);
    container.holder = "held-edit";
    container.release = release;
    Thread held = new Thread(
        () -> skills.setEnabled(HOST, CONTAINER, PROFILE, "pdf", false), "held-edit");
    held.start();
    assertTrue(container.holding.await(5, TimeUnit.SECONDS), "the held edit never reached its read");

    // the other profile's edit finishes while the first is still blocked in its read
    skills.setEnabled(HOST, CONTAINER, "ops", "docx", false);
    assertTrue(container.read(ProfilePaths.profileDir("ops") + "/config.yaml").contains("docx"));
    assertFalse(container.read(CONFIG).contains("pdf"), "the held edit has not written yet");

    release.countDown();
    held.join(5_000);
    assertTrue(container.read(CONFIG).contains("pdf"));
  }

  /**
   * Waits until the thread is parked <em>inside</em> {@code serialized}, not merely parked.
   *
   * <p>Thread state alone is not evidence: these tests drive a Mockito mock, whose own
   * synchronization puts a thread in BLOCKED for a moment, and a test that accepted that would
   * pass with no lock at all. The stack frame is the thing that only exists while one edit is
   * genuinely waiting for another to let go of the profile.
   */
  private static void awaitWaitingOnTheProfileLock(Thread thread) throws InterruptedException {
    for (int attempt = 0; attempt < 500; attempt++) {
      boolean parked = false;
      boolean inSerialized = false;
      for (StackTraceElement frame : thread.getStackTrace()) {
        if (frame.getClassName().startsWith("java.util.concurrent.locks.")) parked = true;
        if (HermesContainerFiles.class.getName().equals(frame.getClassName())
            && "serialized".equals(frame.getMethodName())) {
          inSerialized = true;
        }
      }
      if (parked && inSerialized) return;
      Thread.sleep(10);
    }
    throw new AssertionError("the second edit never waited for the profile lock");
  }

  @Test
  void aWriteThatThrowsStillReleasesTheProfile() {
    // a lock held by a failed edit would wedge every later edit of that profile until restart
    MutableContainer container = new MutableContainer().file(CONFIG, "mcp_servers: {}\n");
    HermesContainerFiles files = new HermesContainerFiles(container.dockerExec);

    assertThrowsAnything(() -> files.serialized(CONTAINER, PROFILE, () -> {
      throw new IllegalStateException("the edit could not be computed");
    }));

    assertEquals("kept", files.serialized(CONTAINER, PROFILE, () -> "kept"));
  }

  // ── the batched read ────────────────────────────────────────────────────

  @Test
  void aBatchedReadAnswersEveryPathAskedForAndNothingElse() {
    MutableContainer container = new MutableContainer()
        .file("/opt/data/SOUL.md", "be useful\n")
        // no trailing newline, and a body carrying blank lines: the framing has to hand back
        // exactly what cat produced, or a profile document comes back subtly altered
        .file("/opt/data/MEMORY.md", "line one\n\nline three")
        .file("/opt/data/config.yaml", "");
    HermesContainerFiles files = new HermesContainerFiles(container.dockerExec);

    Map<String, String> read = files.readFiles(HOST, CONTAINER, List.of(
        "/opt/data/config.yaml", "/opt/data/SOUL.md", "/opt/data/MEMORY.md", "/opt/data/.env"));

    assertEquals("be useful\n", read.get("/opt/data/SOUL.md"));
    assertEquals("line one\n\nline three", read.get("/opt/data/MEMORY.md"));
    assertEquals("", read.get("/opt/data/config.yaml"));
    // absent answers empty, as readFile does: a profile legitimately has no .env
    assertEquals("", read.get("/opt/data/.env"));
    assertEquals(4, read.size());
  }

  @Test
  void anEmptyBatchAsksTheContainerNothing() {
    MutableContainer container = new MutableContainer();
    HermesContainerFiles files = new HermesContainerFiles(container.dockerExec);

    assertEquals(Map.of(), files.readFiles(HOST, CONTAINER, List.of()));
    assertEquals(List.of(), container.operations);
  }

  private static void assertThrowsAnything(Runnable work) {
    try {
      work.run();
    } catch (RuntimeException expected) {
      return;
    }
    throw new AssertionError("expected the work to fail");
  }
}
