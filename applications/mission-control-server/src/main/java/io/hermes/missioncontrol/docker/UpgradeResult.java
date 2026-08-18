package io.hermes.missioncontrol.docker;

/** The outcome of moving one managed container from {@code fromTag} to {@code toTag}. */
public record UpgradeResult(
    String oldContainerId, String newContainerId, String fromTag, String toTag, boolean running) {
}
