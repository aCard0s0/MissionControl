package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.HOST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.AgentSetupDto;
import io.hermes.missioncontrol.agents.api.ApiKeyStatusDto;
import io.hermes.missioncontrol.agents.api.AuthProviderDto;
import io.hermes.missioncontrol.agents.api.CronJobDto;
import io.hermes.missioncontrol.agents.api.SkillDto;
import io.hermes.missioncontrol.agents.api.WebhookSubscriptionDto;
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
 *
 * <p><b>Which files a set must have.</b> Only {@code version.txt} and {@code status.txt} are
 * required, because those are the two the capture script always writes. The gateway log, the
 * skill tree, the schedule and the webhook routes are each skipped when absent, since a profile
 * legitimately has no jobs, no routes or no skills — and the file the script writes is then
 * empty. Those skips are deliberately <em>per file</em>: a {@code assumeTrue} in one of them
 * aborts this whole test method, so one profile captured without a webhook route used to make
 * every other assertion here — for every captured version — report as skipped rather than run.
 * That is the same invisible-green failure the fixtures exist to catch.
 */
class HermesCliFixtureTest {

  private static final Path FIXTURES = Path.of("src/test/resources/fixtures");

  /**
   * What {@code tools/capture-hermes-fixtures.sh} writes in place of a route's HMAC secret.
   * Asserted on below, because the alternative is a live signing key committed to git.
   */
  private static final String REDACTED_SECRET = "redacted-by-capture-hermes-fixtures-sh-0000";

  /** A profile whose webhook listener is on, so the rendered route HOST carries its port. */
  private static final String WEBHOOK_LISTENER_CONFIG = """
      platforms:
        webhook:
          enabled: true
          extra:
            host: "0.0.0.0"
            port: 8644
      """;

  @Test
  void everyCapturedHermesVersionStillParses() throws IOException {
    List<Path> sets = fixtureSets();
    assumeTrue(!sets.isEmpty(), "no captured hermes fixtures — run "
        + "tools/capture-hermes-fixtures.sh <container> to create one");

    for (Path set : sets) {
      String version = set.getFileName().toString().replace("hermes-", "");
      assertTrue(read(set, "version.txt").contains(version),
          set + ": the recorded version does not match the directory it is in");
      // the capture script always writes this one, so a set without it is a broken capture
      // rather than a profile with nothing configured — and it is what keeps this test from
      // passing while asserting nothing
      assertFalse(read(set, "status.txt").isBlank(),
          set + ": no status.txt — re-capture, this set is incomplete");

      assertTheStatusReportParses(set, version);
      assertTheGatewayLogParses(set, version);
      assertTheSkillTreeParses(set, version);
      assertTheScheduleParses(set, version);
      assertTheWebhookRoutesParse(set, version);
    }
  }

  /**
   * The status report feeds every "is this provider configured" badge in the dashboard. A section
   * heading or label that drifts makes the whole list read as unconfigured, which is
   * indistinguishable from a genuinely empty agent.
   */
  private void assertTheStatusReportParses(Path set, String version) throws IOException {
    HermesContainerFiles files = mock(HermesContainerFiles.class);
    when(files.fileExists(any(), anyString(), anyString())).thenReturn(true);
    when(files.readFile(any(), anyString(), anyString())).thenReturn("");
    when(files.exec(any(), anyString(), any()))
        .thenReturn(new ExecResult(0, read(set, "status.txt"), ""));

    AgentSetupDto report = new HermesSetup(files, mock(HermesEnvFile.class))
        .setup(HOST, CONTAINER, "default");

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
    if (captured.isBlank()) return;   // optional: see the class javadoc

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
    if (skillMd.isBlank()) return;   // optional: see the class javadoc

    String skillsDir = "/opt/data/profiles/ops/skills";
    FakeContainer container = new FakeContainer()
        .file(skillsDir + "/captured/SKILL.md", skillMd)
        .file(skillsDir + "/.bundled_manifest", manifest)
        .onCommand("-name SKILL.md", skillsDir + "/captured/SKILL.md\n");

    List<SkillDto> listed = new HermesSkills(container.files(), new HermesConfigEditor())
        .list(HOST, CONTAINER, "ops", Map.of());

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

  /**
   * The schedule, which the Jobs page renders straight out of this file.
   *
   * <p>The trap this pins is hermes' own: only a {@code cron} schedule carries an {@code expr},
   * so a reader that goes for that field reports no schedule at all for the {@code once} and
   * {@code interval} kinds — two thirds of what an operator can create, silently blank.
   */
  private void assertTheScheduleParses(Path set, String version) throws IOException {
    String captured = read(set, "cron-jobs.json");
    if (captured.isBlank()) return;   // optional: a profile may have no jobs at all

    HermesContainerFiles files = mock(HermesContainerFiles.class);
    when(files.readFile(any(), anyString(), anyString())).thenReturn(captured);
    when(files.exec(any(), anyString(), any(), anyBoolean()))
        .thenReturn(new ExecResult(0, "✓ Cron scheduler is running", ""));

    List<CronJobDto> jobs =
        new HermesCron(files, new ObjectMapper()).list(HOST, CONTAINER, "default").jobs();

    assertFalse(jobs.isEmpty(), version + ": the captured schedule parsed to no jobs");
    for (CronJobDto job : jobs) {
      assertFalse(job.id().isBlank(), version + ": a job parsed without an id");
      assertNotNull(job.scheduleKind(), version + ": a job parsed without a schedule kind");
      assertNotNull(job.schedule(), version + ": " + job.id() + " has no schedule to display");
      assertFalse(job.schedule().isBlank(), version + ": " + job.id() + " displays a blank schedule");
      assertNotNull(job.createdAt(), version + ": hermes' timestamp format no longer parses");
    }
    // the captured set deliberately holds one of each kind, because reading the wrong field
    // leaves exactly the other two blank
    assertEquals(List.of("cron", "interval", "once"),
        jobs.stream().map(CronJobDto::scheduleKind).distinct().sorted().toList(),
        version + ": the set no longer covers all three schedule kinds — re-capture with a"
            + " cron, an interval and a once job so this stays a real test");
  }

  /**
   * The webhook routes, plus the one thing about this file that is not about parsing: it holds
   * every route's HMAC signing key in plaintext, so a captured set must carry the capture
   * script's placeholder and never a real one.
   */
  private void assertTheWebhookRoutesParse(Path set, String version) throws IOException {
    String captured = read(set, "webhook-subscriptions.json");
    if (captured.isBlank()) return;   // optional: a profile may have no routes at all

    ObjectMapper json = new ObjectMapper();
    JsonNode root = json.readTree(captured);
    root.fields().forEachRemaining(route -> assertEquals(
        REDACTED_SECRET, route.getValue().path("secret").asText(""),
        version + ": route '" + route.getKey() + "' carries a secret the capture script did not"
            + " scrub — that is a live signing key in a git repository"));

    HermesContainerFiles files = mock(HermesContainerFiles.class);
    when(files.readFile(any(), anyString(), anyString())).thenAnswer(invocation ->
        invocation.getArgument(2, String.class).endsWith("config.yaml")
            ? WEBHOOK_LISTENER_CONFIG : captured);

    List<WebhookSubscriptionDto> routes =
        new HermesWebhooks(files, json, new ProfileInventory(files))
            .list(HOST, CONTAINER, "default").subscriptions();

    assertFalse(routes.isEmpty(), version + ": the captured routes parsed to nothing");
    for (WebhookSubscriptionDto route : routes) {
      assertFalse(route.name().isBlank(), version + ": a route parsed without a name");
      // hermes keys this file by route name, so an array-shaped read yields nameless rows
      assertTrue(route.url().endsWith(":8644/webhooks/" + route.name()), route.url());
      assertTrue(route.secretMasked().startsWith("..."),
          version + ": " + route.name() + " would carry its secret into a listing");
      assertNotNull(route.createdAt(), version + ": hermes' timestamp format no longer parses");
    }
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
