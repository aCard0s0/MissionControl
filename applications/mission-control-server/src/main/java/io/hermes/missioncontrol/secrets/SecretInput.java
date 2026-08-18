package io.hermes.missioncontrol.secrets;

/** A template secret on the way in. Blank/absent {@code value} keeps the stored one. */
public record SecretInput(String key, String value) {
}
