package io.hermes.missioncontrol.docker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.dockerjava.api.exception.BadRequestException;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotAcceptableException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins how a Docker daemon failure becomes an HTTP status.
 *
 * <p>These mappings used to live in {@code ApiExceptionHandlerTest}, alongside the advice that
 * used to own them. They moved with the advice, which is the point: the HTTP layer no longer
 * knows what a docker-java exception is.
 *
 * <p><b>Both advices are registered, and deliberately in the wrong order</b> —
 * {@link ApiExceptionHandler} first, whose {@code RuntimeException} catch-all would claim every
 * exception here. Every assertion below therefore proves two things at once: the mapping, and
 * that {@code @Order} put the specific advice ahead of the catch-all. Registering only the
 * docker advice would still pass while the real application answered 500.
 */
class DockerExceptionAdviceTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(new ApiExceptionHandler(), new DockerExceptionAdvice())
        .build();
  }

  @Test
  void aDockerFailureIsABadGatewayRatherThanTheCatchAllsServerError() throws Exception {
    mvc.perform(get("/boom/docker"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.startsWith("docker daemon error:")));
  }

  @Test
  void dockerNotFoundIsANotFound() throws Exception {
    mvc.perform(get("/boom/docker-not-found"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aDockerConflictIsAConflictNotABadGateway() throws Exception {
    // a duplicate container name is the caller's problem and is actionable; 502 tells them
    // the daemon is broken, which it is not
    mvc.perform(get("/boom/docker-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("container name already in use")));
  }

  @Test
  void aDockerNotModifiedIsNotReportedAsADaemonError() throws Exception {
    // stopping an already-stopped container: nothing failed, the state is simply already
    // what was asked for
    mvc.perform(get("/boom/docker-not-modified"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("container already stopped")));
  }

  /**
   * The exec seam's own translation of the daemon's 409, which exists so the agents package
   * can recognise a stopped container without importing docker-java. It must answer the same
   * status the untranslated exception did.
   */
  @Test
  void aContainerThatIsNotRunningIsAConflict() throws Exception {
    mvc.perform(get("/boom/container-not-running"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("needs a running container")));
  }

  // --- the daemon rejecting us vs. the daemon being broken ----------------------------
  //
  // The DockerException catch-all answered 502 for the whole family, so a malformed image
  // reference — a request the daemon itself refused — was reported as "docker daemon error"
  // and tripped alerting keyed on 5xx.

  @Test
  void aRequestTheDaemonItselfRejectsIsAClientErrorNotAGatewayFailure() throws Exception {
    mvc.perform(get("/boom/docker-bad-request"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("invalid reference format")));
  }

  @Test
  void aNotAcceptableFromTheDaemonIsAlsoAClientError() throws Exception {
    mvc.perform(get("/boom/docker-not-acceptable"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("container is not running")));
  }

  @Test
  void rejectedRegistryCredentialsAreReportedAsAGatewayFailureNotABadRequest() throws Exception {
    // nothing the caller sent is wrong — our registry configuration is
    mvc.perform(get("/boom/docker-unauthorized"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.containsString("registry credentials")));
  }

  /**
   * Nothing this advice handles reaches the other one. The reverse direction — a plain
   * failure still finding the catch-all with the docker advice ahead of it — is pinned by
   * {@code ApiExceptionHandlerTest}.
   */
  @Test
  void theSubtypeHandlersDoNotSwallowTheGeneralDaemonCase() throws Exception {
    mvc.perform(get("/boom/docker")).andExpect(status().isBadGateway());
  }

  @RestController
  @RequestMapping("/boom")
  static class ThrowingController {

    @RequestMapping("/docker")
    void docker() {
      throw new DockerException("daemon exploded", 500);
    }

    @RequestMapping("/docker-not-found")
    void dockerNotFound() {
      throw new NotFoundException("no such container");
    }

    @RequestMapping("/docker-conflict")
    void dockerConflict() {
      throw new ConflictException("container name already in use");
    }

    @RequestMapping("/docker-not-modified")
    void dockerNotModified() {
      throw new NotModifiedException("container already stopped");
    }

    @RequestMapping("/container-not-running")
    void containerNotRunning() {
      throw new ContainerNotRunningException(
          "Hermes command needs a running container: c1",
          new ConflictException("is not running"));
    }

    @RequestMapping("/docker-bad-request")
    void dockerBadRequest() {
      throw new BadRequestException("Status 400: invalid reference format");
    }

    @RequestMapping("/docker-not-acceptable")
    void dockerNotAcceptable() {
      throw new NotAcceptableException("container is not running");
    }

    @RequestMapping("/docker-unauthorized")
    void dockerUnauthorized() {
      throw new UnauthorizedException("unauthorized: incorrect username or password");
    }
  }
}
