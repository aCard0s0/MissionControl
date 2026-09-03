package io.hermes.missioncontrol.common;

import java.util.Locale;

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

  /**
   * A trimmed link, or null when there is nothing in it, refusing any scheme a browser
   * should not follow.
   *
   * <p>Here rather than in either caller because the skill library and the MCP catalog store
   * the same {@code repoUrl} and both render it as an {@code href} — and until this was one
   * rule, they disagreed about it. Angular sanitizes a bound href as well, but a store that
   * will hand this to any client should not be relying on one client's framework to make it
   * safe.
   *
   * <p>Only the scheme is decided here. {@code http://} is admitted alongside
   * {@code https://} so this stays the browser-safety rule and nothing more: whether a
   * particular URL is one anything can *use* belongs to whoever uses it —
   * {@code skills.UpstreamCheck.githubRepo} accepts a narrower set still (https, github.com,
   * two path segments) and answers {@code unsupported} for the rest rather than refusing the
   * save.
   */
  public static String httpLinkOrNull(String raw, String field) {
    String url = blankToNull(raw);
    if (url == null) {
      return null;
    }
    String scheme = url.toLowerCase(Locale.ROOT);
    if (!scheme.startsWith("http://") && !scheme.startsWith("https://")) {
      throw new IllegalArgumentException(field + " must start with http:// or https://");
    }
    return url;
  }
}
