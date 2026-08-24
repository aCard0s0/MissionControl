package io.hermes.missioncontrol.errors;

import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * How every failure that is not a Docker daemon failure becomes an HTTP status.
 *
 * <p>Docker's own mappings live in {@code docker/DockerExceptionAdvice}, beside the client
 * they know about. This advice is deliberately last: its {@code RuntimeException} catch-all
 * would otherwise answer for exceptions a more specific advice exists to handle.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
    return ApiErrors.error(HttpStatus.BAD_REQUEST, e.getMessage());
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
    return ApiErrors.error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
    // a UNIQUE/constraint violation that slipped past an explicit pre-check (e.g. a
    // concurrent create/rename race) — a clean 409, not an opaque 503
    log.warn("constraint violation: {}", ApiErrors.brief(e.getMessage()));
    return ApiErrors.error(HttpStatus.CONFLICT, "that change conflicts with an existing record");
  }

  @ExceptionHandler(ResourceConflictException.class)
  public ResponseEntity<Map<String, String>> conflict(ResourceConflictException e) {
    return ApiErrors.error(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String, String>> unreadableRequest(Exception e) {
    String detail = e instanceof MethodArgumentTypeMismatchException mismatch
        ? mismatch.getName() + " is not a valid " + expectedType(mismatch)
        : "request body is not readable";
    return ApiErrors.error(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, String>> missingParameter(MissingServletRequestParameterException e) {
    return ApiErrors.error(HttpStatus.BAD_REQUEST, e.getParameterName() + " is required");
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
    return ApiErrors.error(HttpStatus.BAD_REQUEST, detail);
  }

  @ExceptionHandler(UpstreamUnavailableException.class)
  public ResponseEntity<Map<String, String>> unavailable(UpstreamUnavailableException e) {
    log.warn("upstream unavailable: {}", e.getMessage());
    return ApiErrors.error(HttpStatus.SERVICE_UNAVAILABLE, ApiErrors.brief(e.getMessage()));
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<Map<String, String>> unexpected(RuntimeException e) {
    // a genuine defect, not a dependency being down — log the trace, answer 500
    log.error("unexpected failure handling request", e);
    return ApiErrors.error(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrors.brief(e.getMessage()));
  }

  private static String expectedType(MethodArgumentTypeMismatchException e) {
    Class<?> required = e.getRequiredType();
    return required == null ? "value" : required.getSimpleName().toLowerCase(java.util.Locale.ROOT);
  }
}
