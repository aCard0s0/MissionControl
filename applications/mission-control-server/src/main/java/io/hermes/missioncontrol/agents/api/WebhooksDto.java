package io.hermes.missioncontrol.agents.api;

import java.util.List;

/** A profile's webhook routes, and the listener that would receive them. */
public record WebhooksDto(List<WebhookSubscriptionDto> subscriptions, WebhookPlatformDto platform) {
}
