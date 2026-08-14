package io.hermes.missioncontrol.hermes;

/**
 * How a stored credential is shown back to the client.
 *
 * <p>One implementation rather than two: {@link HermesSetup} reports keys read from a
 * profile's {@code .env} and {@link HermesProfiles} reports the key belonging to a
 * profile's configured provider. Both reach the same UI, so a difference between them is
 * either a leak in one or a confusing display in the other.
 */
final class Secrets {

  /** Characters of the original value a mask may reveal. */
  private static final int VISIBLE_SUFFIX = 4;

  private Secrets() {
  }

  /**
   * The last few characters of a secret, enough to tell two keys apart and no more.
   *
   * <p>A value too short to have a hidden part reveals nothing at all — returning it in
   * full would disclose the whole secret to anyone who can read the dashboard.
   */
  static String mask(String value) {
    if (value == null || value.isBlank()) return "";
    String trimmed = value.trim();
    if (trimmed.length() <= VISIBLE_SUFFIX) return "...";
    return "..." + trimmed.substring(trimmed.length() - VISIBLE_SUFFIX);
  }
}
