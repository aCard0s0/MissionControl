package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * An outbound webhook target as the dashboard submits it.
 *
 * <p>No {@code secret} field, deliberately. Hermes accepts an inline secret and calls it
 * discouraged in its own schema; a signing key typed into a browser form would be written to
 * {@code config.yaml} in plaintext and read back by every later listing. Only the env var
 * name travels, and the value is set on the Setup page like every other credential.
 *
 * @param url must be absolute http(s). Plain http is allowed because hermes allows it — it
 *     warns and delivers, and a receiver on the container network is a real case
 */
public record OutboundWebhookRequest(
    @Size(max = 60) String name,
    @Pattern(regexp = "https?://\\S+", message = "must be an absolute http:// or https:// URL")
    String url,
    @NotEmpty(message = "needs at least one event") List<String> events,
    @Size(max = 200) String matcher,
    @Min(1) @Max(60) Integer timeout,
    @Pattern(regexp = "|[A-Z][A-Z0-9_]*", message = "must be an environment variable name")
    String secretEnv) {
}
