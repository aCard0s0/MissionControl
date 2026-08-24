package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotAcceptableException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import io.hermes.missioncontrol.errors.ApiErrors;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * How a Docker daemon failure becomes an HTTP status.
 *
 * <p>Lives beside the client it knows about rather than in {@code errors}. The mappings are
 * unchanged — they were simply the only reason the HTTP layer imported docker-java, which
 * made a client upgrade that renamed an exception a change to {@code errors} as well as to
 * this package.
 *
 * <p>Also home to the exec seam's own translations — {@link ContainerNotRunningException} and
 * {@link ContainerCommandFailedException} — for the same reason: they exist so callers do not
 * import docker-java, and this is where their statuses belong.
 *
 * <p>Answers the same {@code {"error": …}} body shape as {@code ApiExceptionHandler}, because
 * a client cannot tell which advice handled its request and should not have to — through
 * {@link ApiErrors}, which is that shape, rather than through a second copy of it.
 *
 * <p>{@link Order} is load-bearing. {@code ApiExceptionHandler} has a
 * {@code RuntimeException} catch-all, and every exception here extends {@code RuntimeException}:
 * Spring consults advice beans in order and takes the most specific handler within the first
 * bean that offers one, so whichever advice is asked first wins outright. Without an explicit
 * precedence the catch-all would answer 500 for a daemon error, which is the reverse of what
 * these mappings exist to prevent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DockerExceptionAdvice {

  private static final Logger log = LoggerFactory.getLogger(DockerExceptionAdvice.class);

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
    return ApiErrors.error(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(DockerException.class)
  public ResponseEntity<Map<String, String>> dockerFailure(DockerException e) {
    log.warn("docker call failed: {}", e.getMessage());
    return ApiErrors.error(HttpStatus.BAD_GATEWAY, "docker daemon error: " + ApiErrors.brief(e.getMessage()));
  }

  @ExceptionHandler({ConflictException.class, NotModifiedException.class})
  public ResponseEntity<Map<String, String>> dockerConflict(DockerException e) {
    return ApiErrors.error(HttpStatus.CONFLICT, ApiErrors.brief(e.getMessage()));
  }

  /** The exec seam's translation of the daemon's 409 for a stopped container. */
  @ExceptionHandler(ContainerNotRunningException.class)
  public ResponseEntity<Map<String, String>> containerNotRunning(ContainerNotRunningException e) {
    return ApiErrors.error(HttpStatus.CONFLICT, ApiErrors.brief(e.getMessage()));
  }

  /**
   * A command inside a container exited non-zero: the CLI refused what was asked, not a defect
   * in this application. Without this these reach {@code ApiExceptionHandler}'s
   * {@code RuntimeException} catch-all, which reports every rejected schedule expression and
   * unknown skill id as a 500 with a stack trace at ERROR.
   */
  @ExceptionHandler(ContainerCommandFailedException.class)
  public ResponseEntity<Map<String, String>> containerCommandFailed(
      ContainerCommandFailedException e) {
    return ApiErrors.error(HttpStatus.BAD_REQUEST, ApiErrors.brief(e.getMessage()));
  }

  /**
   * The daemon rejecting our request is a client error, not a daemon failure. Without
   * this these land on the {@link DockerException} catch-all and a bad image tag is
   * reported as '502 docker daemon error', which reads as "the daemon is broken" and
   * trips any alerting keyed on 5xx.
   */
  @ExceptionHandler({BadRequestException.class, NotAcceptableException.class})
  public ResponseEntity<Map<String, String>> dockerRejectedRequest(DockerException e) {
    return ApiErrors.error(HttpStatus.BAD_REQUEST, ApiErrors.brief(e.getMessage()));
  }

  /** A registry refusing our credentials — a configuration problem, not a bad request. */
  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<Map<String, String>> dockerUnauthorized(UnauthorizedException e) {
    log.warn("docker registry rejected our credentials: {}", e.getMessage());
    return ApiErrors.error(HttpStatus.BAD_GATEWAY,
        "registry credentials were rejected: " + ApiErrors.brief(e.getMessage()));
  }
}
