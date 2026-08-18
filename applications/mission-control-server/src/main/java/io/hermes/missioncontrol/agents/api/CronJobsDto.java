package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * A profile's scheduled jobs, plus whether anything will actually run them.
 *
 * @param jobs             the schedule, newest next-run first
 * @param schedulerRunning false when the gateway is down — hermes stores jobs either way,
 *                         but nothing fires them, which is worth saying on the page rather
 *                         than leaving an operator to wonder why a job never ran
 */
public record CronJobsDto(List<CronJobDto> jobs, boolean schedulerRunning) {
}
