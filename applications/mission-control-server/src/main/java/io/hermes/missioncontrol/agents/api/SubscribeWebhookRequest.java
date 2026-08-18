package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * A new webhook route. Only `name` is required — hermes generates the HMAC secret, which is
 * the path Mission Control always takes: a secret it minted would have to travel through the
 * dashboard to get here.
 */
public record SubscribeWebhookRequest(
    String name,
    String prompt,
    String description,
    List<String> events,
    List<String> skills,
    String deliver,
    String deliverChatId,
    boolean deliverOnly) {
}
