package io.hermes.missioncontrol.credentials.api;

/**
 * One entry of a credential as the API exposes it.
 *
 * <p>{@code value} carries the stored text only when the entry is not secret — a messaging
 * platform's home channel, a base URL. A secret entry reports {@code set} and
 * {@code recoverable} and returns no value, not even a suffix: the dropdown that consumes this
 * posts an id back and never handles key material itself.
 */
public record CredentialEntryDto(
    String key,
    String value,
    boolean secret,
    boolean set,
    boolean recoverable) {
}
