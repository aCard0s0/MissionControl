package io.hermes.missioncontrol.docker;

/** One-shot stats sample; network counters are cumulative (client computes rates). */
public record StatsDto(
    double cpuPercent,
    double ramMb,
    double ramTotalMb,
    long rxBytes,
    long txBytes,
    long sampledAt) {
}
