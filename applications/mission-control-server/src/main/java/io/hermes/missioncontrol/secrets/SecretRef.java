package io.hermes.missioncontrol.secrets;

/**
 * A template secret as returned to the client. The raw value (and any prefix of
 * it) never leaves the server — only whether a value is stored ({@code set}) and
 * whether it can still be decrypted with the current key ({@code recoverable}).
 * A {@code set} but not {@code recoverable} secret means MC_SECRET_KEY changed or
 * the ciphertext is corrupt, so the value must be re-entered before it is usable.
 */
public record SecretRef(String key, boolean set, boolean recoverable) {
}
