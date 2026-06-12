package io.hermes.missioncontrol.web;

import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler({NoSuchElementException.class, NotFoundException.class})
  public ResponseEntity<Map<String, String>> notFound(Exception e) {
    return error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(DockerException.class)
  public ResponseEntity<Map<String, String>> dockerFailure(DockerException e) {
    log.warn("docker call failed: {}", e.getMessage());
    return error(HttpStatus.BAD_GATEWAY, "docker daemon error: " + brief(e.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> unavailable(RuntimeException e) {
    log.warn("request failed: {}", e.toString());
    return error(HttpStatus.SERVICE_UNAVAILABLE, brief(e.getMessage()));
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", message == null ? "request failed" : message));
  }

  private static String brief(String message) {
    if (message == null) return "request failed";
    String firstLine = message.lines().findFirst().orElse(message);
    return firstLine.length() > 300 ? firstLine.substring(0, 300) : firstLine;
  }
}
