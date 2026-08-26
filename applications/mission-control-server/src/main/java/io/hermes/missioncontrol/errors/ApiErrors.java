package io.hermes.missioncontrol.errors;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The one failure body this API answers with, and the one rule for trimming a message into it.
 *
 * <p>Both exception advices produced it, from byte-for-byte identical private copies. They are
 * separate beans on purpose — {@code docker/DockerExceptionAdvice} lives beside the client
 * whose exception types it knows, so that a docker-java upgrade is not also a change to this
 * package — but a client cannot tell which advice handled its request and should not be able
 * to, which makes the shape they share worth stating once.
 */
public final class ApiErrors {

  /** Enough of a message to identify the failure; the rest belongs in the log. */
  private static final int MAX_MESSAGE = 300;

  private ApiErrors() {}

  public static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", message == null ? "request failed" : message));
  }

  /**
   * A message's first line, capped. Docker and CLI failures arrive with stack traces and
   * multi-line output attached, none of which a client can act on.
   */
  public static String brief(String message) {
    return brief(message, MAX_MESSAGE, "request failed");
  }

  /**
   * As {@link #brief(String)}, with the cap and the nothing-to-say answer the caller needs.
   *
   * <p>Four packages trimmed a message this way with four private copies that disagreed on
   * both — a 500-character database column, a log line that wants no cap at all, a 200-character
   * pull status — which is a difference in those two values, not in the rule. Blank counts as
   * nothing to say: a docker error body arrives with a trailing newline, and the empty string
   * the old copies let through reads in a log or a status field as "no error at all".
   */
  public static String brief(String message, int max, String fallback) {
    if (message == null) return fallback;
    String line = message.lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
    if (line.isEmpty()) return fallback;
    return line.length() > max ? line.substring(0, max) : line;
  }
}
