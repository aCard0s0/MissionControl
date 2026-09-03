package io.hermes.missioncontrol.agents.web;

import io.hermes.missioncontrol.agents.HermesCron;
import io.hermes.missioncontrol.agents.api.CreateCronJobRequest;
import io.hermes.missioncontrol.agents.api.CronJobsDto;
import io.hermes.missioncontrol.agents.api.UpdateCronJobRequest;
import io.hermes.missioncontrol.docker.DockerHostRef;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A profile's scheduled jobs. Every mutation answers with the whole schedule as hermes now
 * holds it, so the dashboard never has to guess what a create or an edit produced — the id,
 * the parsed schedule and the next run are all hermes' to decide.
 */
@RestController
@RequestMapping("/api/agents/{hostId}/{containerId}/{name}/cron")
class AgentCronController {

  private final HermesCron cron;

  AgentCronController(HermesCron cron) {
    this.cron = cron;
  }

  @GetMapping
  public CronJobsDto list(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name) {
    return cron.list(host, containerId, name);
  }

  @PostMapping
  public CronJobsDto create(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @RequestBody CreateCronJobRequest request) {
    return cron.create(host, containerId, name, request);
  }

  @PatchMapping("/{jobId}")
  public CronJobsDto update(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String jobId,
      @RequestBody UpdateCronJobRequest request) {
    return cron.update(host, containerId, name, jobId, request);
  }

  @PostMapping("/{jobId}/pause")
  public CronJobsDto pause(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String jobId) {
    return cron.setEnabled(host, containerId, name, jobId, false);
  }

  @PostMapping("/{jobId}/resume")
  public CronJobsDto resume(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String jobId) {
    return cron.setEnabled(host, containerId, name, jobId, true);
  }

  /** Asks for the job on the next scheduler tick, rather than waiting for its schedule. */
  @PostMapping("/{jobId}/run")
  public CronJobsDto runNow(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String jobId) {
    return cron.runNow(host, containerId, name, jobId);
  }

  @DeleteMapping("/{jobId}")
  public CronJobsDto remove(
      @PathVariable("hostId") DockerHostRef host,
      @PathVariable String containerId,
      @PathVariable String name,
      @PathVariable String jobId) {
    return cron.remove(host, containerId, name, jobId);
  }
}
