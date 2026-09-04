package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.SkillFilesDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * Writing a library skill onto a profile, and reading one back off it.
 *
 * <p>The relative path of each file is the only string in this application that an operator
 * types and that is then concatenated into a container path, so the rejection cases are the
 * point of this class. Each of them asserts that <em>nothing ran</em>: a path checked as it
 * is written would leave a half-deployed skill behind the rejection, and hermes would still
 * try to load it.
 */
class HermesSkillFilesTest {

  private static final String OPS_SKILLS = "/opt/data/profiles/ops/skills";

  private HermesSkills skills(FakeContainer container) {
    return new HermesSkills(container.files(), new HermesConfigEditor());
  }

  private static Map<String, String> files(String... pathsAndBodies) {
    Map<String, String> files = new LinkedHashMap<>();
    for (int i = 0; i < pathsAndBodies.length; i += 2) {
      files.put(pathsAndBodies[i], pathsAndBodies[i + 1]);
    }
    return files;
  }

  /** The argv of every write, as `path => content`. */
  private static Map<String, String> written(FakeContainer container) {
    Map<String, String> written = new LinkedHashMap<>();
    for (List<String> command : container.executed()) {
      if (command.size() >= 5 && command.get(2).contains("mkdir -p")) {
        written.put(command.get(command.size() - 2), command.getLast());
      }
    }
    return written;
  }

  // ── rejection ──────────────────────────────────────────────────────────────

  @Test
  void aPathThatCouldEscapeTheSkillDirectoryIsRejectedBeforeAnythingIsWritten() {
    for (String path : List.of(
        "../../../etc/passwd",     // the classic
        "..",                      // the segment on its own
        "a/../../b",               // climbing from inside a legal-looking prefix
        "/etc/passwd",             // absolute: the leading empty segment fails
        "a//b",                    // an empty middle segment
        "a/",                      // an empty trailing segment
        "..\\..\\windows",         // backslashes are not separators here, and fail the charset
        ".hidden/SKILL.md",        // a dot-file segment: `find` skips these, so it would vanish
        "-rf",                     // could be read as a flag if it ever reached an argv head
        "a/b/c/d")) {              // deeper than `find -maxdepth 3` can see
      FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

      assertThrows(IllegalArgumentException.class,
          () -> skills(container).writeSkillFiles(
              HOST, CONTAINER, "ops", "pdf", files(path, "x")),
          "path=" + path);
      assertEquals(List.of(), container.executed(),
          "nothing may run before every path in the set is accepted: path=" + path);
    }
  }

  @Test
  void oneBadPathRejectsTheWholeSetRatherThanTheFilesAfterIt() {
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).writeSkillFiles(HOST, CONTAINER, "ops", "pdf",
            files("SKILL.md", "# ok", "../escape", "x", "scripts/run.sh", "echo")));

    assertEquals(List.of(), container.executed(),
        "the good file before the bad one must not have been written");
  }

  @Test
  void anInvalidSkillNameIsRejected() {
    for (String name : List.of("../pdf", "pdf/../..", "", ".hidden", "-rf")) {
      FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

      assertThrows(IllegalArgumentException.class,
          () -> skills(container).writeSkillFiles(
              HOST, CONTAINER, "ops", name, files("SKILL.md", "x")),
          "name=" + name);
      assertEquals(List.of(), container.executed(), "name=" + name);
    }
  }

  @Test
  void anEmptyFileSetIsRejectedRatherThanWritingAnEmptyDirectory() {
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).writeSkillFiles(HOST, CONTAINER, "ops", "pdf", Map.of()));
    assertThrows(IllegalArgumentException.class,
        () -> skills(container).writeSkillFiles(HOST, CONTAINER, "ops", "pdf", null));
  }

  @Test
  void anUnknownProfileIs404RatherThanADirectoryMkdirWouldMint() {
    // writeFile runs `mkdir -p $(dirname)`, so without the profile guard a typo in the
    // profile name would silently create /opt/data/profiles/<typo>/skills/pdf/
    FakeContainer container = new FakeContainer();   // no profile dir declared

    assertThrows(NoSuchElementException.class,
        () -> skills(container).writeSkillFiles(
            HOST, CONTAINER, "ops", "pdf", files("SKILL.md", "# pdf")));

    assertTrue(written(container).isEmpty(), "nothing was written: " + container.executed());
  }

  @Test
  void aSkillLargerThanTheByteBudgetIsRefusedRatherThanTruncated() {
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");
    String huge = "x".repeat(600 * 1024);

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).writeSkillFiles(
            HOST, CONTAINER, "ops", "pdf", files("SKILL.md", huge)));
    assertEquals(List.of(), container.executed());
  }

  @Test
  void aSkillWithMoreFilesThanTheCapIsRefusedRatherThanPartlyWritten() {
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");
    Map<String, String> many = new LinkedHashMap<>();
    for (int i = 0; i < 65; i++) {
      many.put("f" + i + ".md", "x");
    }

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).writeSkillFiles(HOST, CONTAINER, "ops", "pdf", many));
    assertEquals(List.of(), container.executed());
  }

  @Test
  void readingASkillWithMoreFilesThanTheCapIsRefusedRatherThanTruncated() {
    // a silently half-imported skill is worse than a failed import: it would deploy later
    // and be missing the half nobody was told about
    StringBuilder listing = new StringBuilder();
    for (int i = 0; i < 65; i++) {
      listing.append("f").append(i).append(".md\n");
    }
    FakeContainer container = new FakeContainer()
        .dir(OPS_SKILLS + "/pdf")
        .onCommand("cd \"$d\"", listing.toString());

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).readSkillFiles(HOST, CONTAINER, "ops", "pdf"));
  }

  @Test
  void readingASkillLargerThanTheByteBudgetIsRefused() {
    // six files that together pass the 512KB skill budget
    FakeContainer container = new FakeContainer().dir(OPS_SKILLS + "/pdf");
    StringBuilder listing = new StringBuilder();
    for (int i = 0; i < 6; i++) {
      container.file(OPS_SKILLS + "/pdf/part" + i + ".md", "x".repeat(100 * 1024));
      listing.append("part").append(i).append(".md\n");
    }
    container.onCommand("cd \"$d\"", listing.toString());

    assertThrows(IllegalArgumentException.class,
        () -> skills(container).readSkillFiles(HOST, CONTAINER, "ops", "pdf"));
  }

  // ── writing ────────────────────────────────────────────────────────────────

  @Test
  void everyFileLandsUnderTheSkillDirectoryWithoutItHavingToExistFirst() {
    // the difference from updateContent, which requires the skill to already resolve:
    // a deploy's whole point is that the directory is not there yet
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

    skills(container).writeSkillFiles(HOST, CONTAINER, "ops", "pdf",
        files("SKILL.md", "# pdf", "scripts/run.sh", "echo hi", "refs/a/b.md", "notes"));

    assertEquals(Map.of(
            OPS_SKILLS + "/pdf/SKILL.md", "# pdf",
            OPS_SKILLS + "/pdf/scripts/run.sh", "echo hi",
            OPS_SKILLS + "/pdf/refs/a/b.md", "notes"),
        written(container));
  }

  @Test
  void theDefaultProfileWritesAtTheHermesHomeRatherThanUnderProfiles() {
    // `default` is not a directory under /opt/data/profiles — it is the hermes home itself
    FakeContainer container = new FakeContainer().dir("/opt/data");

    skills(container).writeSkillFiles(
        HOST, CONTAINER, "default", "pdf", files("SKILL.md", "# pdf"));

    assertEquals(List.of("/opt/data/skills/pdf/SKILL.md"),
        List.copyOf(written(container).keySet()));
  }

  // ── reading back ───────────────────────────────────────────────────────────

  @Test
  void readingASkillsFilesBatchesTheBodiesRatherThanOneCatPerFile() {
    FakeContainer container = new FakeContainer()
        .dir(OPS_SKILLS + "/pdf")
        .file(OPS_SKILLS + "/pdf/SKILL.md", "# pdf")
        .file(OPS_SKILLS + "/pdf/scripts/run.sh", "echo hi")
        .onCommand("cd \"$d\"", "SKILL.md\nscripts/run.sh\n");

    SkillFilesDto read = skills(container).readSkillFiles(HOST, CONTAINER, "ops", "pdf");

    assertEquals(Map.of("SKILL.md", "# pdf", "scripts/run.sh", "echo hi"), read.files());
    assertEquals(List.of(), read.skipped());
    // resolve the dir, list it, then ONE batched read for every body — the count grows
    // with the layout, never with the number of files
    assertEquals(3, container.executed().size(), "execs: " + container.executed());
    assertTrue(container.executed().getLast().get(2).startsWith("marker="),
        "the last exec is the batched read: " + container.executed().getLast());
  }

  @Test
  void aBinaryFileIsReportedSkippedRatherThanImportedAsCorruption() {
    // the exec pipe is UTF-8; a PNG that round-trips through it is no longer a PNG, and
    // silently storing that in the library is the one failure here that loses data
    FakeContainer container = new FakeContainer()
        .dir(OPS_SKILLS + "/pdf")
        .file(OPS_SKILLS + "/pdf/SKILL.md", "# pdf")
        .file(OPS_SKILLS + "/pdf/logo.png", " PNG  ")
        .onCommand("cd \"$d\"", "SKILL.md\nlogo.png\n");

    SkillFilesDto read = skills(container).readSkillFiles(HOST, CONTAINER, "ops", "pdf");

    assertEquals(Map.of("SKILL.md", "# pdf"), read.files());
    assertEquals(List.of("logo.png"), read.skipped());
  }

  @Test
  void readingASkillThatDoesNotResolveIsRejected() {
    FakeContainer container = new FakeContainer().dir("/opt/data/profiles/ops");

    // a 404, not a 400: the name is well-formed, there is just no such skill on this profile
    assertThrows(NoSuchElementException.class,
        () -> skills(container).readSkillFiles(HOST, CONTAINER, "ops", "nope"));
  }
}
