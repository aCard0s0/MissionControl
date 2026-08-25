package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * A profile's webhooks in both directions: the inbound routes and the listener that would
 * receive them, and the outbound targets hermes pushes signed lifecycle events to.
 *
 * <p>One document because they are one page and one read — the inbound half comes from
 * {@code webhook_subscriptions.json}, the outbound half from {@code config.yaml}.
 */
public record WebhooksDto(
    List<WebhookSubscriptionDto> subscriptions,
    WebhookPlatformDto platform,
    List<OutboundWebhookDto> outbound) {
}
