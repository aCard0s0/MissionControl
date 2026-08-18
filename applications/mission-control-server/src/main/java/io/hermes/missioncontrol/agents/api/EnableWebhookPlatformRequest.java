package io.hermes.missioncontrol.agents.api;

/** Turns the profile's webhook listener on or off, and where it binds. */
public record EnableWebhookPlatformRequest(boolean enabled, String host, Integer port) {
}
