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
    if (message == null) return "request failed";
    String firstLine = message.lines().findFirst().orElse(message);
    return firstLine.length() > MAX_MESSAGE ? firstLine.substring(0, MAX_MESSAGE) : firstLine;
  }
}
