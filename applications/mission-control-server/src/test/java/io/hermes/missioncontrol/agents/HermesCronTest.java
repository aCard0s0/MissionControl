package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.CreateCronJobRequest;
import io.hermes.missioncontrol.agents.api.CronJobDto;
import io.hermes.missioncontrol.agents.api.CronJobsDto;
import io.hermes.missioncontrol.agents.api.UpdateCronJobRequest;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A profile's schedule: what hermes' own {@code cron/jobs.json} means, and what argv each
 * mutation sends.
 *
 * <p>The listing fixture is captured from {@code nousresearch/hermes-agent} v0.16.0 rather
 * than typed here — see {@code fixtures/hermes-0.16.0/cron-jobs.json}. That matters for one
 * reason a synthetic fixture missed entirely: only a {@code cron} schedule carries an
 * {@code expr}. A {@code once} job stores {@code run_at} and an {@code interval} job stores
 * {@code minutes}, so reading {@code expr} reported no schedule at all for two of the three
 * kinds hermes can create.
 */
class HermesCronTest {

  private static final String URL = "unix:///var/run/docker.sock";
  private static final String CONTAINER = "c1";

  /** Records every argv and answers reads from a canned jobs.json. */
  private static final class Exec extends HermesContainerFiles {
    private final List<List<String>> commands = new ArrayList<>();
    private String jobsJson = "";
    private String cronStatus = "✓ Cron scheduler is running";

    Exec() {
      super(null);
    }

    @Override
    String readFile(String url, String containerId, String path) {
      commands.add(List.of("readFile", path));
      return jobsJson;
    }

    @Override
    ExecResult exec(String url, String containerId, List<String> command, boolean check) {
      commands.add(command);
      if (command.contains("status")) return new ExecResult(0, cronStatus, "");
      return new ExecResult(0, "", "");
    }

    /**
     * The mutation, not the read: every write re-lists afterwards, so the last hermes
     * command on the container is always the {@code cron status} that listing runs.
     */
    List<String> mutationCommand() {
      return commands.stream()
          .filter(c -> !c.isEmpty() && "hermes".equals(c.getFirst()) && !c.contains("status"))
          .findFirst().orElseThrow();
    }
  }

  private final Exec exec = new Exec();
  private final HermesCron cron = new HermesCron(exec, new ObjectMapper());

  private static String fixture() throws IOException {
    return Files.readString(
        Path.of("src/test/resources/fixtures/hermes-0.16.0/cron-jobs.json"));
  }

  // ── reading what hermes stored ─────────────────────────────────────────────

  @Test
  void everyScheduleKindReportsSomethingToShow() throws IOException {
    exec.jobsJson = fixture();

    Map<String, CronJobDto> byKind = new HashMap<>();
    for (CronJobDto job : cron.list(URL, CONTAINER, "default").jobs()) {
      byKind.put(job.scheduleKind(), job);
    }

    // the whole point of the captured fixture: none of these may be null
    assertEquals("0 9 * * *", byKind.get("cron").schedule());
    assertEquals("once in 30m", byKind.get("once").schedule());
    assertEquals("every 120m", byKind.get("interval").schedule());
  }

  @Test
  void aJobCarriesTheFieldsThePageRenders() throws IOException {
    exec.jobsJson = fixture();

    CronJobDto digest = cron.list(URL, CONTAINER, "default").jobs().stream()
        .filter(j -> "morning digest".equals(j.name())).findFirst().orElseThrow();

    assertEquals("Summarize overnight alerts and page if anything is still firing", digest.prompt());
    assertEquals("telegram", digest.deliver());
    assertEquals("scheduled", digest.state());
    assertTrue(digest.enabled());
    assertNull(digest.repeatTimes());        // unbounded, which the UI shows as ∞
    assertEquals(0, digest.repeatDone());
    assertTrue(digest.createdAt() > 0);
    assertTrue(digest.nextRunAt() > digest.createdAt());
    assertNull(digest.lastRunAt());          // never run
    assertNull(digest.lastStatus());
  }

  @Test
  void aPausedJobIsReportedAsDisabledRatherThanOmitted() throws IOException {
    exec.jobsJson = fixture();

    CronJobDto paused = cron.list(URL, CONTAINER, "default").jobs().stream()
        .filter(j -> !j.enabled()).findFirst().orElseThrow();

    assertEquals("paused", paused.state());
    assertEquals("interval", paused.scheduleKind());
  }

  @Test
  void aBoundedRepeatKeepsItsRemainingCount() throws IOException {
    exec.jobsJson = fixture();

    CronJobDto watchdog = cron.list(URL, CONTAINER, "default").jobs().stream()
        .filter(j -> "watchdog".equals(j.name())).findFirst().orElseThrow();

    assertEquals(5, watchdog.repeatTimes());
    assertEquals(0, watchdog.repeatDone());
  }

  @Test
  void jobsAreOrderedByWhatRunsNext() throws IOException {
    exec.jobsJson = fixture();

    List<Long> nextRuns = cron.list(URL, CONTAINER, "default").jobs().stream()
        .map(CronJobDto::nextRunAt).toList();

    assertEquals(
        nextRuns.stream().sorted(Comparator.nullsLast(Comparator.naturalOrder())).toList(),
        nextRuns);
  }

  @Test
  void anEmptyOrUnreadableFileReadsAsAnEmptySchedule() {
    exec.jobsJson = "";
    assertTrue(cron.list(URL, CONTAINER, "default").jobs().isEmpty());

    // a half-written file during a hermes write must not 500 the page that shows it
    exec.jobsJson = "{\"jobs\": [{\"id\": \"a\", ";
    assertTrue(cron.list(URL, CONTAINER, "default").jobs().isEmpty());
  }

  @Test
  void readsTheProfilesOwnScheduleFile() throws IOException {
    exec.jobsJson = fixture();

    cron.list(URL, CONTAINER, "ops");

    assertTrue(exec.commands.contains(
        List.of("readFile", "/opt/data/profiles/ops/cron/jobs.json")));
  }

  // ── whether anything will fire them ───────────────────────────────────────

  @Test
  void saysTheSchedulerIsDownWhenTheGatewayIsNotRunning() {
    // a stored job nothing fires is the failure an operator cannot otherwise see
    exec.cronStatus = "✗ Gateway is not running — cron jobs will NOT fire";

    assertFalse(cron.list(URL, CONTAINER, "default").schedulerRunning());
  }

  @Test
  void saysTheSchedulerIsUpWhenHermesReportsItRunning() {
    exec.cronStatus = "✓ Cron scheduler is running (gateway up)";

    assertTrue(cron.list(URL, CONTAINER, "default").schedulerRunning());
  }

  // ── what each mutation asks hermes to do ──────────────────────────────────

  @Test
  void aCreatePassesTheScheduleAndPromptPositionally() {
    cron.create(URL, CONTAINER, "default", new CreateCronJobRequest(
        "0 9 * * *", "Summarize alerts", "digest", "telegram", 5, List.of("web-research")));

    assertEquals(List.of("hermes", "cron", "create", "0 9 * * *", "Summarize alerts",
        "--name", "digest", "--deliver", "telegram", "--repeat", "5",
        "--skill", "web-research"), exec.mutationCommand());
  }

  @Test
  void aNamedProfileScopesEveryCommandWithMinusP() {
    cron.create(URL, CONTAINER, "ops", new CreateCronJobRequest(
        "30m", "Check disk", null, null, null, null));

    assertEquals(List.of("hermes", "-p", "ops", "cron", "create", "30m", "Check disk"),
        exec.mutationCommand());
  }

  @Test
  void blankOptionsAreLeftOffTheCommandLineEntirely() {
    cron.create(URL, CONTAINER, "default", new CreateCronJobRequest(
        "30m", null, "  ", "", null, List.of("", "  ")));

    assertEquals(List.of("hermes", "cron", "create", "30m"), exec.mutationCommand());
  }

  @Test
  void aScheduleIsRequired() {
    assertThrows(IllegalArgumentException.class, () -> cron.create(URL, CONTAINER, "default",
        new CreateCronJobRequest("  ", "do it", null, null, null, null)));
    assertThrows(IllegalArgumentException.class, () -> cron.create(URL, CONTAINER, "default",
        new CreateCronJobRequest(null, "do it", null, null, null, null)));
  }

  @Test
  void anEditSendsOnlyTheFieldsThatChanged() {
    cron.update(URL, CONTAINER, "default", "abc123",
        new UpdateCronJobRequest(null, "new prompt", null, null, null, null));

    assertEquals(List.of("hermes", "cron", "edit", "abc123", "--prompt", "new prompt"),
        exec.mutationCommand());
  }

  @Test
  void anEditWithNothingToChangeTouchesNoContainer() {
    cron.update(URL, CONTAINER, "default", "abc123",
        new UpdateCronJobRequest(null, null, null, null, null, null));

    assertTrue(exec.commands.stream().noneMatch(c -> c.contains("edit")));
  }

  @Test
  void pausingUsesHermesOwnVerb() {
    cron.setEnabled(URL, CONTAINER, "default", "abc123", false);

    assertEquals(List.of("hermes", "cron", "pause", "abc123"), exec.mutationCommand());
  }

  @Test
  void resumingUsesHermesOwnVerb() {
    cron.setEnabled(URL, CONTAINER, "default", "abc123", true);

    assertEquals(List.of("hermes", "cron", "resume", "abc123"), exec.mutationCommand());
  }

  @Test
  void removeAddressesTheJobById() {
    cron.remove(URL, CONTAINER, "default", "abc123");

    assertEquals(List.of("hermes", "cron", "remove", "abc123"), exec.mutationCommand());
  }

  @Test
  void runNowAsksForTheNextTickRatherThanTheSchedule() {
    cron.runNow(URL, CONTAINER, "default", "abc123");

    assertEquals(List.of("hermes", "cron", "run", "abc123"), exec.mutationCommand());
  }

  @Test
  void aJobIdThatCouldCarryShellOrFlagMeaningIsRefused() {
    // ids reach us from a URL path segment and go straight into an argv
    for (String hostile : List.of("--help", "a b", "a;rm -rf /", "", "a".repeat(65), "a/b")) {
      assertThrows(IllegalArgumentException.class,
          () -> cron.remove(URL, CONTAINER, "default", hostile), hostile);
    }
  }

  @Test
  void aProfileNameThatCouldEscapeTheProfilesDirIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> cron.list(URL, CONTAINER, "../../etc"));
  }

  @Test
  void everyMutationAnswersWithTheScheduleHermesNowHolds() throws IOException {
    exec.jobsJson = fixture();

    CronJobsDto after = cron.remove(URL, CONTAINER, "default", "abc123");

    // the dashboard never guesses what a write produced — ids, parsed schedules and
    // next-run times are all hermes' to decide
    assertEquals(3, after.jobs().size());
  }
}
