package io.hermes.missioncontrol.hermes;

/** A template secret on the way in. Blank/absent {@code value} keeps the stored one. */
public record SecretInput(String key, String value) {
}
