package io.hermes.missioncontrol.agents.api;

import java.util.List;

/**
 * One outbound webhook target, from the {@code hooks.outbound} list in a profile's
 * {@code config.yaml}. The mirror of {@link WebhookSubscriptionDto}: an inbound route wakes
 * the agent when the world changes, an outbound target tells the world when the agent does
 * something. Hermes signs the POST with HMAC-SHA256 when a secret is configured.
 *
 * <p>There is no enabled flag, because hermes has none — removing the target is the off
 * switch, and inventing a disabled state the agent would ignore is worse than not having one.
 *
 * @param name        optional label, shown in hermes' own logs
 * @param url         where hermes POSTs
 * @param events      lifecycle events this target receives
 * @param matcher     optional regex, honoured for {@code pre_tool_call}/{@code post_tool_call}
 * @param timeout     per-attempt seconds; hermes clamps to [1, 60]
 * @param secretEnv   name of the env var holding the signing secret, never its value
 * @param literalSecret true when the config carries an inline {@code secret:} instead of an
 *     env var. The value is never read out: the dashboard will not show it and will not
 *     overwrite it, so an operator who set one by hand keeps it, and everyone else is
 *     steered to {@code secret_env}
 */
public record OutboundWebhookDto(
    String name,
    String url,
    List<String> events,
    String matcher,
    Integer timeout,
    String secretEnv,
    boolean literalSecret) {
}
