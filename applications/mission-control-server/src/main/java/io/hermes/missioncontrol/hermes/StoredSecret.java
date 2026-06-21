package io.hermes.missioncontrol.hermes;

/** A template secret as persisted: env var key + the {@code enc:v1:} ciphertext. */
public record StoredSecret(String key, String enc) {
}
