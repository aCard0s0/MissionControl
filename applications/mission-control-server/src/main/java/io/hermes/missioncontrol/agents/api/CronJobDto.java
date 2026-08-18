package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * One scheduled job as hermes records it in {@code <profile>/cron/jobs.json}.
 *
 * @param id            hermes-minted job id, stable across edits
 * @param name          operator-facing name, or null when the job was created without one
 * @param prompt        what the agent is asked to do, or null for a {@code --script} job
 * @param schedule      hermes' own rendering of the schedule — "0 9 * * *", "once in 30m",
 *                      "every 120m". This is the display string, deliberately: only a
 *                      {@code cron} schedule carries an {@code expr}, while {@code once}
 *                      carries a timestamp and {@code interval} a minute count, so `display`
 *                      is the one field present for every kind and the one an editor can
 *                      hand back to {@code --schedule}
 * @param scheduleKind  cron, once or interval — which of those shapes hermes parsed it into
 * @param deliver       delivery target: origin, local, or platform[:chat_id]
 * @param enabled       false once paused
 * @param state         hermes' lifecycle word — scheduled, paused, running, done
 * @param repeatTimes   total runs requested, or null for unbounded
 * @param repeatDone    runs completed so far
 * @param createdAt     epoch millis
 * @param nextRunAt     epoch millis, or null when the job will not run again
 * @param lastRunAt     epoch millis, or null before the first run
 * @param lastStatus    hermes' word for how the last run ended, or null
 * @param lastError     why the last run failed, or null
 * @param skills        skills attached to the run
 */
public record CronJobDto(
    String id,
    String name,
    String prompt,
    String schedule,
    String scheduleKind,
    String deliver,
    boolean enabled,
    String state,
    Integer repeatTimes,
    int repeatDone,
    Long createdAt,
    Long nextRunAt,
    Long lastRunAt,
    String lastStatus,
    String lastError,
    List<String> skills) {
}
