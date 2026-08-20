package io.hermes.missioncontrol.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The dashboard's own log tail over HTTP, in the shape the container tail already answers. */
class ServerLogControllerTest {

  private ServerLogBuffer buffer;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    buffer = mock(ServerLogBuffer.class);
    AppProperties props = new AppProperties("", "unix:///sock", "hermes/image", "hermes", "9.9.9", true);
    mvc = MockMvcBuilders.standaloneSetup(new ServerLogController(buffer, props))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void theTailIsAnsweredInTheSameShapeAsAContainerTail() throws Exception {
    when(buffer.tail(200, null)).thenReturn(
        List.of(new LogLineDto(1_700_000_000_000L, "warn", "ContainerInventory", "hiding demo")));

    mvc.perform(get("/api/server/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].ts").value(1_700_000_000_000L))
        .andExpect(jsonPath("$[0].level").value("warn"))
        .andExpect(jsonPath("$[0].source").value("ContainerInventory"))
        .andExpect(jsonPath("$[0].msg").value("hiding demo"));
  }

  @Test
  void theTailSizeAndLevelArePassedThroughToTheBuffer() throws Exception {
    when(buffer.tail(25, "error")).thenReturn(List.of());

    mvc.perform(get("/api/server/logs?tail=25&level=error")).andExpect(status().isOk());

    verify(buffer).tail(eq(25), eq("error"));
  }

  @Test
  void aNonNumericTailIsARejectedRequestRatherThanAServerError() throws Exception {
    // the advice maps the type mismatch; without it this is an opaque 500
    mvc.perform(get("/api/server/logs?tail=lots")).andExpect(status().isBadRequest());
  }

  @Test
  void theInfoEndpointReportsWhatTheLogPageHeaderShows() throws Exception {
    mvc.perform(get("/api/server/info"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value("9.9.9"))
        .andExpect(jsonPath("$.retained").value(ServerLogBuffer.CAPACITY))
        .andExpect(jsonPath("$.startedAt").isNumber());
  }
}
