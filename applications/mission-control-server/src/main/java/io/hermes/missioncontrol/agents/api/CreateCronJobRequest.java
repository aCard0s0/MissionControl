package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * A new scheduled job. `schedule` and `prompt` are what hermes needs; the rest are
 * optional and omitted from the command line when blank.
 */
public record CreateCronJobRequest(
    String schedule,
    String prompt,
    String name,
    String deliver,
    Integer repeat,
    List<String> skills) {
}
