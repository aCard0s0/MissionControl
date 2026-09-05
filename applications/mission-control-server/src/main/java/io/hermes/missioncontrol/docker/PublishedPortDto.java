package io.hermes.missioncontrol.docker;

/** One container port the daemon publishes on the host, as the fleet listing reports it. */
public record PublishedPortDto(int containerPort, String hostIp, int hostPort) {
}
