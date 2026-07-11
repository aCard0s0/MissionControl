package io.hermes.missioncontrol.mcp;

import java.util.List;

/** Compose healthcheck fields accepted by the structured editor. */
public record HealthcheckSpec(
    List<String> test,
    String interval,
    String timeout,
    Integer retries,
    String startPeriod) {}
