package io.hermes.missioncontrol.errors;

import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotAcceptableException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
    // a UNIQUE/constraint violation that slipped past an explicit pre-check (e.g. a
    // concurrent create/rename race) — a clean 409, not an opaque 503
    log.warn("constraint violation: {}", brief(e.getMessage()));
    return error(HttpStatus.CONFLICT, "that change conflicts with an existing record");
  }

  @ExceptionHandler(ResourceConflictException.class)
  public ResponseEntity<Map<String, String>> conflict(ResourceConflictException e) {
    return error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(DockerException.class)
  public ResponseEntity<Map<String, String>> dockerFailure(DockerException e) {
    log.warn("docker call failed: {}", e.getMessage());
    return error(HttpStatus.BAD_GATEWAY, "docker daemon error: " + brief(e.getMessage()));
  }

  @ExceptionHandler({ConflictException.class, NotModifiedException.class})
  public ResponseEntity<Map<String, String>> dockerConflict(DockerException e) {
    return error(HttpStatus.CONFLICT, brief(e.getMessage()));
  }

  /**
   * The daemon rejecting our request is a client error, not a daemon failure. Without
   * this these land on the {@link DockerException} catch-all and a bad image tag is
   * reported as '502 docker daemon error', which reads as "the daemon is broken" and
   * trips any alerting keyed on 5xx.
   */
  @ExceptionHandler({BadRequestException.class, NotAcceptableException.class})
  public ResponseEntity<Map<String, String>> dockerRejectedRequest(DockerException e) {
    return error(HttpStatus.BAD_REQUEST, brief(e.getMessage()));
  }

  /** A registry refusing our credentials — a configuration problem, not a bad request. */
  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<Map<String, String>> dockerUnauthorized(UnauthorizedException e) {
    log.warn("docker registry rejected our credentials: {}", e.getMessage());
    return error(HttpStatus.BAD_GATEWAY, "registry credentials were rejected: " + brief(e.getMessage()));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> unreadableRequest(Exception e) {
    String detail = e instanceof MethodArgumentTypeMismatchException mismatch
        ? mismatch.getName() + " is not a valid " + expectedType(mismatch)
        : "request body is not readable";
    return error(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, String>> missingParameter(MissingServletRequestParameterException e) {
    return error(HttpStatus.BAD_REQUEST, e.getParameterName() + " is required");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException e) {
    // without this the advice never sees bean-validation failures at all — they are not
    // RuntimeExceptions, so Spring's default resolver answers with its own body shape
    // and the client loses the {"error": ...} contract every other failure honours
    String detail = e.getBindingResult().getFieldErrors().stream()
        .map(field -> field.getField() + " " + field.getDefaultMessage())
        .findFirst()
        .orElse("request body is invalid");
    return error(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable(UpstreamUnavailableException e) {
    log.warn("upstream unavailable: {}", e.getMessage());
    return error(HttpStatus.SERVICE_UNAVAILABLE, brief(e.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
    // a genuine defect, not a dependency being down — log the trace, answer 500
    log.error("unexpected failure handling request", e);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, brief(e.getMessage()));
  }

  private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", message == null ? "request failed" : message));
  }

  private static String expectedType(MethodArgumentTypeMismatchException e) {
    Class<?> required = e.getRequiredType();
    return required == null ? "value" : required.getSimpleName().toLowerCase(java.util.Locale.ROOT);
  }

  private static String brief(String message) {
    if (message == null) return "request failed";
    String firstLine = message.lines().findFirst().orElse(message);
    return firstLine.length() > 300 ? firstLine.substring(0, 300) : firstLine;
  }
}
