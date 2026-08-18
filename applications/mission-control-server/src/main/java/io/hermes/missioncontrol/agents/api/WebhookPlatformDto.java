package io.hermes.missioncontrol.agents.api;

/**
 * The profile's webhook listener, which has to be on before any route exists.
 *
 * @param enabled   whether {@code platforms.webhook} is on in the profile config
 * @param host      interface hermes binds inside the container
 * @param port      port hermes listens on inside the container
 * @param published false whenever Mission Control cannot see a host port mapped to the
 *                  listener — a route is then configured but unreachable from outside the
 *                  docker network until an operator exposes it themselves
 */
public record WebhookPlatformDto(boolean enabled, String host, Integer port, boolean published) {
}
