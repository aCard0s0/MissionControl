package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * One webhook route, as hermes records it in {@code webhook_subscriptions.json}.
 *
 * <p>The HMAC secret is deliberately <b>not</b> here. Hermes stores it in plaintext and an
 * operator does need it to configure the sending provider, but it must not ride along in a
 * listing the dashboard polls — one endpoint reveals it, on request.
 *
 * @param name         route name, which is also its URL segment
 * @param description  what the route is for
 * @param url          where a provider should POST, as hermes renders it
 * @param events       event types accepted, empty meaning all of them
 * @param prompt       prompt template, with {dot.notation} references into the payload
 * @param skills       skills loaded for the run
 * @param deliver      delivery target: log, telegram, discord, slack, …
 * @param deliverOnly  true when the payload is delivered without running the agent
 * @param secretMasked last characters of the HMAC secret, enough to tell two apart
 * @param createdAt    epoch millis
 */
public record WebhookSubscriptionDto(
    String name,
    String description,
    String url,
    List<String> events,
    String prompt,
    List<String> skills,
    String deliver,
    boolean deliverOnly,
    String secretMasked,
    Long createdAt) {
}
