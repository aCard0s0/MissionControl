package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.CreateCronJobRequest;
import io.hermes.missioncontrol.agents.api.CronJobDto;
import io.hermes.missioncontrol.agents.api.CronJobsDto;
import io.hermes.missioncontrol.agents.api.UpdateCronJobRequest;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A profile's scheduled jobs, which hermes owns end to end.
 *
 * <p><b>Reads go to the file, writes go through the CLI.</b> Hermes keeps the schedule in
 * {@code <profile>/cron/jobs.json}, a plain JSON document with every field this API needs —
 * so listing reads that rather than parsing the boxed table {@code hermes cron list} prints,
 * which is presentation and would drift on any release.
 *
 * <p>Writes are the opposite: {@code hermes cron create/edit} parses the schedule
 * expression ('30m', 'every 2h', '0 9 * * *'), mints the job id and computes the next run.
 * Writing jobs.json directly would mean reimplementing all three and getting them subtly
 * wrong, so every mutation shells out and the file is re-read afterwards.
 */
@Component
public class HermesCron {

  private static final Logger log = LoggerFactory.getLogger(HermesCron.class);

  /**
   * Job ids are hermes-minted hex, and they reach us from a URL path segment before going
   * straight into an argv. The leading character is deliberately not a hyphen: an id of
   * {@code --help} — or any other flag — would otherwise be read by hermes as an option
   * rather than as the job to act on.
   */
  private static final Pattern JOB_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}");

  private final HermesContainerFiles files;
  private final ObjectMapper objectMapper;

  HermesCron(HermesContainerFiles files, ObjectMapper objectMapper) {
    this.files = files;
    this.objectMapper = objectMapper;
  }

  /** The schedule, and whether the gateway that fires it is up. */
  public CronJobsDto list(String url, String containerId, String profileName) {
    return new CronJobsDto(read(url, containerId, profileName),
        schedulerRunning(url, containerId, profileName));
  }

  public CronJobsDto create(
      String url, String containerId, String profileName, CreateCronJobRequest request) {
    String schedule = required(request.schedule(), "schedule");
    List<String> command = new ArrayList<>(List.of("cron", "create", schedule));
    if (notBlank(request.prompt())) command.add(request.prompt());
    addOption(command, "--name", request.name());
    addOption(command, "--deliver", request.deliver());
    if (request.repeat() != null) addOption(command, "--repeat", String.valueOf(request.repeat()));
    for (String skill : skills(request.skills())) addOption(command, "--skill", skill);

    run(url, containerId, profileName, command);
    return list(url, containerId, profileName);
  }

  public CronJobsDto update(
      String url, String containerId, String profileName, String jobId,
      UpdateCronJobRequest request) {
    List<String> command = new ArrayList<>(List.of("cron", "edit", requireJobId(jobId)));
    addOption(command, "--schedule", request.schedule());
    addOption(command, "--prompt", request.prompt());
    addOption(command, "--name", request.name());
    addOption(command, "--deliver", request.deliver());
    if (request.repeat() != null) addOption(command, "--repeat", String.valueOf(request.repeat()));
    // --skill replaces the set, which is what an editor's "these are the skills" means
    for (String skill : skills(request.skills())) addOption(command, "--skill", skill);
    if (command.size() == 3) return list(url, containerId, profileName);   // nothing to change

    run(url, containerId, profileName, command);
    return list(url, containerId, profileName);
  }

  /** Pauses or resumes one job. Hermes keeps a paused job in the file, disabled. */
  public CronJobsDto setEnabled(
      String url, String containerId, String profileName, String jobId, boolean enabled) {
    run(url, containerId, profileName,
        List.of("cron", enabled ? "resume" : "pause", requireJobId(jobId)));
    return list(url, containerId, profileName);
  }

  public CronJobsDto remove(String url, String containerId, String profileName, String jobId) {
    run(url, containerId, profileName, List.of("cron", "remove", requireJobId(jobId)));
    return list(url, containerId, profileName);
  }

  /** Asks for the job to fire on the next scheduler tick rather than at its schedule. */
  public CronJobsDto runNow(String url, String containerId, String profileName, String jobId) {
    run(url, containerId, profileName, List.of("cron", "run", requireJobId(jobId)));
    return list(url, containerId, profileName);
  }

  // ── reading ────────────────────────────────────────────────────────────────

  private List<CronJobDto> read(String url, String containerId, String profileName) {
    String path = ProfilePaths.cronJobsFile(profileName);
    String json = files.readFile(url, containerId, path);
    if (json == null || json.isBlank()) return List.of();
    List<CronJobDto> jobs = new ArrayList<>();
    try {
      JsonNode root = objectMapper.readTree(json);
      for (JsonNode job : root.path("jobs")) {
        jobs.add(toDto(job));
      }
    } catch (Exception e) {
      // a half-written file during a hermes write, or a shape from a newer release: an
      // empty schedule reads better than a 500 on the page that shows it
      log.warn("unreadable cron jobs.json for profile {}: {}", profileName, e.getMessage());
      return List.of();
    }
    jobs.sort(Comparator.comparing(
        CronJobDto::nextRunAt, Comparator.nullsLast(Comparator.naturalOrder())));
    return jobs;
  }

  private CronJobDto toDto(JsonNode job) {
    JsonNode schedule = job.path("schedule");
    JsonNode repeat = job.path("repeat");
    List<String> skills = new ArrayList<>();
    for (JsonNode skill : job.path("skills")) skills.add(skill.asText());
    return new CronJobDto(
        job.path("id").asText(),
        text(job, "name"),
        text(job, "prompt"),
        firstNonBlank(text(job, "schedule_display"), text(schedule, "display")),
        text(schedule, "kind"),
        text(job, "deliver"),
        job.path("enabled").asBoolean(true),
        text(job, "state"),
        repeat.path("times").isNumber() ? repeat.path("times").asInt() : null,
        repeat.path("completed").asInt(0),
        epochMillis(text(job, "created_at")),
        epochMillis(text(job, "next_run_at")),
        epochMillis(text(job, "last_run_at")),
        text(job, "last_status"),
        firstNonBlank(text(job, "last_error"), text(job, "last_delivery_error")),
        skills);
  }

  /**
   * Whether the gateway is up, because a stored job that nothing fires is the one failure
   * an operator cannot see on the page. {@code cron status} exists to answer exactly this,
   * so its one line is read rather than the job table's footer.
   */
  private boolean schedulerRunning(String url, String containerId, String profileName) {
    try {
      ExecResult result = run(url, containerId, profileName, List.of("cron", "status"), false);
      String out = (result.stdout() + result.stderr()).toLowerCase();
      return out.contains("running") && !out.contains("not running");
    } catch (RuntimeException e) {
      log.debug("cron status unavailable for profile {}: {}", profileName, e.getMessage());
      return false;
    }
  }

  // ── writing ────────────────────────────────────────────────────────────────

  private ExecResult run(
      String url, String containerId, String profileName, List<String> hermesArgs) {
    return run(url, containerId, profileName, hermesArgs, true);
  }

  private ExecResult run(
      String url, String containerId, String profileName, List<String> hermesArgs, boolean check) {
    List<String> command = new ArrayList<>(ProfilePaths.hermesCli(profileName));
    command.addAll(hermesArgs);
    return files.exec(url, containerId, command, check);
  }

  private static List<String> skills(List<String> skills) {
    if (skills == null) return List.of();
    return skills.stream().filter(HermesCron::notBlank).map(String::trim).toList();
  }

  private static void addOption(List<String> command, String flag, String value) {
    if (notBlank(value)) {
      command.add(flag);
      command.add(value.trim());
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String required(String value, String field) {
    if (!notBlank(value)) throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }

  private static String requireJobId(String jobId) {
    if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
      throw new IllegalArgumentException("invalid cron job id");
    }
    return jobId;
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isNull() || value.isMissingNode() ? null : value.asText();
  }

  private static String firstNonBlank(String first, String second) {
    return notBlank(first) ? first : (notBlank(second) ? second : null);
  }

  /** Hermes writes ISO-8601 with an offset; the dashboard works in epoch millis. */
  private static Long epochMillis(String isoTimestamp) {
    if (!notBlank(isoTimestamp)) return null;
    try {
      return OffsetDateTime.parse(isoTimestamp).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(isoTimestamp).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }
  }
}
