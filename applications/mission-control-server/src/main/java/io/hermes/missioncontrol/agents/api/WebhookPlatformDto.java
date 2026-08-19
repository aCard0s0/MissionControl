package io.hermes.missioncontrol.agents.api;

/**
 * The profile's webhook listener, which has to be on before any route exists.
 *
 * @param enabled   whether {@code platforms.webhook} is on in the profile config
 * @param host      interface hermes binds inside the container
 * @param port      port hermes listens on inside the container
 * @param published whether anything outside the docker network can reach the listener.
 *                  <b>Always false today</b>, deliberately: Mission Control publishes no port
 *                  for an agent container and does not inspect mappings an operator added by
 *                  hand. The field exists so the page states that plainly instead of showing a
 *                  route URL that implies it is live. See docs/architecture.md, "Exposing a
 *                  webhook listener is the operator's job" — the listener is per profile and
 *                  its port is only chosen once an operator enables it, long after the
 *                  container docker would have to have been created with a mapping.
 */
public record WebhookPlatformDto(boolean enabled, String host, Integer port, boolean published) {
}
