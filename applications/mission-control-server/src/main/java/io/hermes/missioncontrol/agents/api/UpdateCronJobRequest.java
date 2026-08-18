package io.hermes.missioncontrol.agents.api;

import java.util.List;

/** Fields to change on an existing job. Every one is optional — a null is left alone. */
public record UpdateCronJobRequest(
    String schedule,
    String prompt,
    String name,
    String deliver,
    Integer repeat,
    List<String> skills) {
}
