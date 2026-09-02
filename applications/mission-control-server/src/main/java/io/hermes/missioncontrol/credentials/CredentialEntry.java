package io.hermes.missioncontrol.credentials;

/**
 * One variable a credential carries: the {@code .env} key it fills, and the value to write.
 *
 * <p>{@code value} is an {@code enc:v1:} envelope when {@link #secret} and plaintext when not.
 * Which of the two it is decides what may leave the server: a non-secret entry's value is
 * returned to the client, a secret entry's never is. A messaging platform's home channel is the
 * case that makes the distinction worth having — it belongs with the token, and hiding it
 * behind a "stored" flag would make the picker useless for it.
 */
public record CredentialEntry(String key, String value, boolean secret) {
}
