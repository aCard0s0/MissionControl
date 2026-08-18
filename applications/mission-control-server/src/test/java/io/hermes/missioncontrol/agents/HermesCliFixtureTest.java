package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.URL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The parsers, against output captured from a real hermes container.
 *
 * <p>Every other test of these parsers feeds them text we typed ourselves, which proves the rules
 * we believe hermes follows and nothing about what it actually prints.
 * {@link HermesSetupTest} says so in its own javadoc. So a hermes release can move a section
 * heading, rename a row label or reshape the gateway log, and the whole suite stays green while
 * the dashboard reports a configured agent as unconfigured.
 *
 * <p>These fixtures close that gap by provenance: {@code tools/capture-hermes-fixtures.sh} dumps
 * the real output into {@code src/test/resources/fixtures/hermes-<version>/}, and this test parses
 * whatever is there. On a hermes bump you re-capture, and format drift arrives as a reviewable
 * diff plus a failure here rather than as a silent misread. The assertions are deliberately about
 * the parse being <em>non-degenerate</em> — a changed format makes these readers return nothing,
 * which is exactly the failure mode that is otherwise invisible.
 *
 * <p>Captured content is redacted by the script and reviewed by hand: what remains is hermes' own
 * vocabulary — section markers, row labels, ✓/✗ marks, its banner and its bundled skill names.
 */
class HermesCliFixtureTest {

  private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

  @Test
  void everyCapturedHermesVersionStillParses() throws IOException {
    List<Path> sets = fixtureSets();
    assumeTrue(!sets.isEmpty(), "no captured hermes fixtures — run "
        + "tools/capture-hermes-fixtures.sh <container> to create one");

    for (Path set : sets) {
      String version = set.getFileName().toString().replace("hermes-", "");
      assertTrue(read(set, "version.txt").contains(version),
          set + ": the recorded version does not match the directory it is in");

      assertTheStatusReportParses(set, version);
      assertTheGatewayLogParses(set, version);
      assertTheSkillTreeParses(set, version);
    }
  }

  /**
   * The status report feeds every "is this provider configured" badge in the dashboard. A section
   * heading or label that drifts makes the whole list read as unconfigured, which is
   * indistinguishable from a genuinely empty agent.
   */
  private void assertTheStatusReportParses(Path set, String version) throws IOException {
    HermesContainerFiles files = mock(HermesContainerFiles.class);
    when(files.fileExists(anyString(), anyString(), anyString())).thenReturn(true);
    when(files.readFile(anyString(), anyString(), anyString())).thenReturn("");
    when(files.exec(anyString(), anyString(), any()))
        .thenReturn(new ExecResult(0, read(set, "status.txt"), ""));

    AgentSetupDto report = new HermesSetup(files, mock(HermesEnvFile.class))
        .setup(URL, CONTAINER, "default");

    assertFalse(report.apiKeys().isEmpty(), version + ": no API-key rows parsed");
    assertFalse(report.authProviders().isEmpty(), version + ": no auth providers parsed");
    assertFalse(report.apiKeyProviders().isEmpty(), version
        + ": no API-key providers parsed — hermes prints that section with its own label"
        + " vocabulary, distinct from the API Keys section");
    assertFalse(report.messaging().isEmpty(), version + ": no messaging platforms parsed");

    // hermes marks each row with ✓ or ✗; a row that parsed but carries neither means the mark
    // moved and every badge is now guesswork
    assertTrue(report.authProviders().stream().anyMatch(AuthProviderDto::ok),
            version + ": the captured report has a logged-in provider, so one row must read ok")
        ;
    assertTrue(report.authProviders().stream().anyMatch(provider -> !provider.ok()),
        version + ": and one must read not-ok");

    // the indented lines under a row (Auth file:, Error:, Refreshed:) are detail, not rows
    for (AuthProviderDto provider : report.authProviders()) {
      assertFalse(provider.label().endsWith(":"),
          version + ": a detail line became a row: " + provider.label());
    }
    for (ApiKeyStatusDto key : report.apiKeys()) {
      assertFalse(key.label().endsWith(":"), version + ": a detail line became a row: " + key.label());
    }
  }

  /** The per-profile gateway log: a timestamp, two spaces, then the message. */
  private void assertTheGatewayLogParses(Path set, String version) throws IOException {
    String captured = read(set, "gateway.log");
    assumeTrue(!captured.isBlank(), version + ": no gateway log was captured");

    List<LogLineDto> lines = HermesGatewayLogs.parse("default", captured);

    assertFalse(lines.isEmpty(), version + ": the captured gateway log parsed to nothing");
    assertTrue(lines.stream().allMatch(line -> line.ts() > 0),
        version + ": a line kept a timestamp it could not read");
    assertTrue(lines.stream().allMatch(line -> "default".equals(line.source())));
    // hermes writes records whose message is empty; those must be dropped rather than rendered
    assertTrue(lines.size() < captured.lines().count(),
        version + ": blank-message records were not dropped");
  }

  /** Skill identity comes from SKILL.md frontmatter, and "bundled" from the manifest. */
  private void assertTheSkillTreeParses(Path set, String version) throws IOException {
    String skillMd = read(set, "skill.md");
    String manifest = read(set, "bundled_manifest.txt");
    assumeTrue(!skillMd.isBlank(), version + ": no SKILL.md was captured");

    String skillsDir = "/opt/data/profiles/ops/skills";
    FakeContainer container = new FakeContainer()
        .file(skillsDir + "/captured/SKILL.md", skillMd)
        .file(skillsDir + "/.bundled_manifest", manifest)
        .onCommand("-name SKILL.md", skillsDir + "/captured/SKILL.md\n");

    List<SkillDto> listed = new HermesSkills(container.files(), new HermesConfigEditor())
        .list(URL, CONTAINER, "ops", Map.of());

    assertEquals(1, listed.size(), version + ": the captured skill did not list");
    SkillDto skill = listed.getFirst();
    // the frontmatter name wins over the directory, which is why a renamed skill still resolves.
    // The captured file is deliberately placed in a directory called 'captured' so this is a
    // real comparison rather than a coincidence.
    String declaredName = skillMd.lines()
        .filter(line -> line.startsWith("name:"))
        .map(line -> line.substring("name:".length()).trim())
        .findFirst().orElseThrow(() -> new AssertionError(version + ": no name in the frontmatter"));
    assertEquals(declaredName, skill.name(), version + ": the frontmatter name must win");
    assertFalse("captured".equals(skill.name()), version + ": the directory name was used instead");
    assertFalse(skill.description().isBlank(), version + ": the description did not parse");
    assertFalse(skill.version().isBlank(), version + ": the version did not parse");
    assertEquals(manifest.contains(skill.name() + ":") ? "bundled" : "user", skill.source(),
        version + ": the manifest decides bundled vs user");
  }

  private static List<Path> fixtureSets() throws IOException {
    if (!Files.isDirectory(FIXTURES)) return List.of();
    try (Stream<Path> entries = Files.list(FIXTURES)) {
      return entries.filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith("hermes-"))
          .sorted()
          .toList();
    }
  }

  private static String read(Path set, String name) throws IOException {
    Path file = set.resolve(name);
    return Files.exists(file) ? Files.readString(file) : "";
  }
}
