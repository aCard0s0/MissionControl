package io.hermes.missioncontrol.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.agents.api.CreateCronJobRequest;
import io.hermes.missioncontrol.agents.api.CronJobDto;
import io.hermes.missioncontrol.agents.api.CronJobsDto;
import io.hermes.missioncontrol.agents.api.UpdateCronJobRequest;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ApiErrors;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
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
  private final HermesCli cli;
  private final ObjectMapper objectMapper;

  HermesCron(HermesContainerFiles files, HermesCli cli, ObjectMapper objectMapper) {
    this.files = files;
    this.cli = cli;
    this.objectMapper = objectMapper;
  }

  /** The schedule, and whether the gateway that fires it is up. */
  public CronJobsDto list(DockerHostRef host, String containerId, String profileName) {
    return new CronJobsDto(read(host, containerId, profileName),
        schedulerRunning(host, containerId, profileName));
  }

  public CronJobsDto create(
      DockerHostRef host, String containerId, String profileName, CreateCronJobRequest request) {
    String schedule = required(request.schedule(), "schedule");
    List<String> command = new ArrayList<>(List.of("cron", "create"));
    HermesCli.addOption(command, "--name", request.name());
    HermesCli.addOption(command, "--deliver", request.deliver());
    if (request.repeat() != null) HermesCli.addOption(command, "--repeat", String.valueOf(request.repeat()));
    for (String skill : skills(request.skills())) HermesCli.addOption(command, "--skill", skill);
    // `--` ends option parsing, so a schedule or prompt that reads like a flag is a positional.
    // Without it `--help` was one: hermes printed its usage, exited 0, and the dashboard
    // reported a job created that does not exist. Checked against v0.20.5 (2026.8.19).
    command.add("--");
    command.add(schedule);
    if (HermesCli.notBlank(request.prompt())) command.add(request.prompt());

    mutate(host, containerId, profileName, command);
    return list(host, containerId, profileName);
  }

  public CronJobsDto update(
      DockerHostRef host, String containerId, String profileName, String jobId,
      UpdateCronJobRequest request) {
    List<String> command = new ArrayList<>(List.of("cron", "edit", requireJobId(jobId)));
    HermesCli.addOption(command, "--schedule", request.schedule());
    HermesCli.addOption(command, "--prompt", request.prompt());
    HermesCli.addOption(command, "--name", request.name());
    HermesCli.addOption(command, "--deliver", request.deliver());
    if (request.repeat() != null) HermesCli.addOption(command, "--repeat", String.valueOf(request.repeat()));
    // --skill replaces the set, which is what an editor's "these are the skills" means
    for (String skill : skills(request.skills())) HermesCli.addOption(command, "--skill", skill);
    if (command.size() == 3) return list(host, containerId, profileName);   // nothing to change

    mutate(host, containerId, profileName, command);
    return list(host, containerId, profileName);
  }

  /** Pauses or resumes one job. Hermes keeps a paused job in the file, disabled. */
  public CronJobsDto setEnabled(
      DockerHostRef host, String containerId, String profileName, String jobId, boolean enabled) {
    mutate(host, containerId, profileName,
        List.of("cron", enabled ? "resume" : "pause", requireJobId(jobId)));
    return list(host, containerId, profileName);
  }

  public CronJobsDto remove(DockerHostRef host, String containerId, String profileName, String jobId) {
    mutate(host, containerId, profileName, List.of("cron", "remove", requireJobId(jobId)));
    return list(host, containerId, profileName);
  }

  /** Asks for the job to fire on the next scheduler tick rather than at its schedule. */
  public CronJobsDto runNow(DockerHostRef host, String containerId, String profileName, String jobId) {
    mutate(host, containerId, profileName, List.of("cron", "run", requireJobId(jobId)));
    return list(host, containerId, profileName);
  }

  /**
   * Runs one {@code hermes cron} mutation and reads its verdict off stdout, because the exit
   * code carries none: hermes v0.20.5 (2026.8.19) exits 0 for an invalid schedule
   * ({@code Failed to create job: Invalid schedule '…'}) and for an unknown id
   * ({@code Job not found: …}, {@code Failed to pause job: … not found}) exactly as it does for
   * {@code Created job: …}. Without this every one of those answered 200 and the page showed
   * the unchanged schedule as though the change had landed.
   *
   * <p>Keyed on the failure wording, not the success wording, so a rewording upstream fails
   * open to today's behaviour rather than turning every success into a 400.
   */
  private void mutate(
      DockerHostRef host, String containerId, String profileName, List<String> command) {
    String verdict = ApiErrors.brief(cli.run(host, containerId, profileName, command).stdout(), 300, "");
    if (verdict.contains("not found")) throw new NoSuchElementException(verdict);
    if (verdict.startsWith("Failed")) throw new IllegalArgumentException(verdict);
  }

  // ── reading ────────────────────────────────────────────────────────────────

  private List<CronJobDto> read(DockerHostRef host, String containerId, String profileName) {
    String path = ProfilePaths.cronJobsFile(profileName);
    String json = files.readFile(host, containerId, path);
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
        HermesCli.text(job, "name"),
        HermesCli.text(job, "prompt"),
        firstNonBlank(HermesCli.text(job, "schedule_display"), HermesCli.text(schedule, "display")),
        HermesCli.text(schedule, "kind"),
        HermesCli.text(job, "deliver"),
        job.path("enabled").asBoolean(true),
        HermesCli.text(job, "state"),
        repeat.path("times").isNumber() ? repeat.path("times").asInt() : null,
        repeat.path("completed").asInt(0),
        HermesCli.epochMillis(HermesCli.text(job, "created_at")),
        HermesCli.epochMillis(HermesCli.text(job, "next_run_at")),
        HermesCli.epochMillis(HermesCli.text(job, "last_run_at")),
        HermesCli.text(job, "last_status"),
        firstNonBlank(HermesCli.text(job, "last_error"), HermesCli.text(job, "last_delivery_error")),
        skills);
  }

  /**
   * Whether the gateway is up, because a stored job that nothing fires is the one failure
   * an operator cannot see on the page. {@code cron status} exists to answer exactly this,
   * so its one line is read rather than the job table's footer.
   */
  private boolean schedulerRunning(DockerHostRef host, String containerId, String profileName) {
    try {
      ExecResult result = cli.run(host, containerId, profileName, List.of("cron", "status"), false);
      String out = (result.stdout() + result.stderr()).toLowerCase();
      return out.contains("running") && !out.contains("not running");
    } catch (RuntimeException e) {
      log.debug("cron status unavailable for profile {}: {}", profileName, e.getMessage());
      return false;
    }
  }

  // ── writing ────────────────────────────────────────────────────────────────

  private static List<String> skills(List<String> skills) {
    if (skills == null) return List.of();
    return skills.stream().filter(HermesCli::notBlank).map(String::trim).toList();
  }



  private static String required(String value, String field) {
    if (!HermesCli.notBlank(value)) throw new IllegalArgumentException(field + " is required");
    return value.trim();
  }

  private static String requireJobId(String jobId) {
    if (jobId == null || !JOB_ID.matcher(jobId).matches()) {
      throw new IllegalArgumentException("invalid cron job id");
    }
    return jobId;
  }


  private static String firstNonBlank(String first, String second) {
    return HermesCli.notBlank(first) ? first : (HermesCli.notBlank(second) ? second : null);
  }

}
