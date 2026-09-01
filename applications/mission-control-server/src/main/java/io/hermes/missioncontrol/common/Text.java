package io.hermes.missioncontrol.common;

/** Small string handling that several controllers were each keeping a copy of. */
public final class Text {

  private Text() {}

  /**
   * A trimmed value, or null when there is nothing in it.
   *
   * <p>Optional text arrives two ways — a missing key, and a field an editor cleared, which
   * sends {@code ""}. Both mean absent, and storing one as null and the other as an empty
   * string makes every later read check for two things.
   */
  public static String blankToNull(String raw) {
    return raw == null || raw.isBlank() ? null : raw.trim();
  }
}
