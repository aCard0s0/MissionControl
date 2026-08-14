package io.hermes.missioncontrol.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pins the exception-to-HTTP contract every frontend call depends on. The stub controller
 * exists so each handler can be reached in isolation, without standing up a real one.
 */
class ApiExceptionHandlerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void illegalArgumentIsABadRequest() throws Exception {
    mvc.perform(get("/boom/illegal-argument"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("that argument is no good"));
  }

  @Test
  void noSuchElementIsANotFound() throws Exception {
    mvc.perform(get("/boom/no-such-element"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown docker host: dh-9"));
  }

  @Test
  void dockerNotFoundIsANotFound() throws Exception {
    mvc.perform(get("/boom/docker-not-found"))
        .andExpect(status().isNotFound());
  }

  @Test
  void aConstraintViolationIsAConflictNotAnOpaqueFailure() throws Exception {
    mvc.perform(get("/boom/data-integrity"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("that change conflicts with an existing record"));
  }

  @Test
  void resourceConflictIsAConflict() throws Exception {
    mvc.perform(get("/boom/resource-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("a container with that name already exists"));
  }

  @Test
  void aDockerFailureIsABadGateway() throws Exception {
    mvc.perform(get("/boom/docker"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error").value(
            org.hamcrest.Matchers.startsWith("docker daemon error:")));
  }

  @Test
  void anUnreachableDependencyIsServiceUnavailable() throws Exception {
    mvc.perform(get("/boom/upstream"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("docker host not connected"));
  }

  @Test
  void anUnexpectedFailureIsAServerErrorNotServiceUnavailable() throws Exception {
    // 503 here would tell the operator to retry a request that will never succeed
    mvc.perform(get("/boom/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("index 4 out of bounds"));
  }

  @Test
  void aValidationFailureKeepsTheErrorBodyShape() throws Exception {
    mvc.perform(post("/boom/validated")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("name")));
  }

  @Test
  void aMessagelessFailureStillProducesAnErrorBody() throws Exception {
    mvc.perform(get("/boom/no-message"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("request failed"));
  }

  @Test
  void aMultiLineMessageIsTruncatedToItsFirstLine() throws Exception {
    mvc.perform(get("/boom/multi-line"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("first line only"));
  }

  record ValidatedBody(@NotBlank String name) {}

  @RestController
  @RequestMapping("/boom")
  static class ThrowingController {

    @RequestMapping("/illegal-argument")
    void illegalArgument() {
      throw new IllegalArgumentException("that argument is no good");
    }

    @RequestMapping("/no-such-element")
    void noSuchElement() {
      throw new NoSuchElementException("unknown docker host: dh-9");
    }

    @RequestMapping("/docker-not-found")
    void dockerNotFound() {
      throw new NotFoundException("no such container");
    }

    @RequestMapping("/data-integrity")
    void dataIntegrity() {
      throw new DataIntegrityViolationException("UNIQUE constraint failed: docker_hosts.url");
    }

    @RequestMapping("/resource-conflict")
    void resourceConflict() {
      throw new ResourceConflictException("a container with that name already exists");
    }

    @RequestMapping("/docker")
    void docker() {
      throw new DockerException("daemon said no", 500);
    }

    @RequestMapping("/upstream")
    void upstream() {
      throw new UpstreamUnavailableException("docker host not connected");
    }

    @RequestMapping("/unexpected")
    void unexpected() {
      throw new IndexOutOfBoundsException("index 4 out of bounds");
    }

    @RequestMapping("/no-message")
    void noMessage() {
      throw new NullPointerException();
    }

    @RequestMapping("/multi-line")
    void multiLine() {
      throw new IllegalStateException("first line only\nstack detail the client should not see");
    }

    @RequestMapping(value = "/validated", method = org.springframework.web.bind.annotation.RequestMethod.POST)
    void validated(@Valid @RequestBody ValidatedBody body) {
      throw new AssertionError("validation should have rejected this before the body ran");
    }

    @RequestMapping("/echo/{id}")
    String echo(@PathVariable String id) {
      return id;
    }
  }
}
