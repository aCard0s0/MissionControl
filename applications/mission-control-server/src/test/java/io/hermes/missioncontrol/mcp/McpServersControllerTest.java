package io.hermes.missioncontrol.mcp;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The MCP catalog endpoints. What lives only at this layer is the 202-vs-200 split that tells
 * the UI whether to start polling, and the routing — notably that
 * {@code /retained-resources/{id}} does not fall through to the catalog-entry routes.
 *
 * <p>The deletion protocol itself is {@link McpServerDeletionTest}'s: it used to be written
 * here because it was written in the controller.
 */
class McpServersControllerTest {

  private McpRegistryService registry;
  private McpServerDeletion deletion;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    registry = mock(McpRegistryService.class);
    deletion = mock(McpServerDeletion.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new McpServersController(registry, deletion))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static McpServerDto server(String id, String kind, String operationState) {
    return new McpServerDto(id, "files", "desc", null, kind, "dh-local", "svc", "http",
        "http://files:8080", "http://files:8080", "img:1", null, List.of(), List.of(), null,
        List.of(), 8080, null, "/mcp", null, List.of(), List.of(), List.of(), null, List.of(),
        "running", "running", operationState, null, 1L, 1L, false, "ok", null, 5L, 3L, 1L, 2L);
  }

  private static final String BODY = """
      {"name":"files","kind":"external","transport":"http","url":"https://files.internal/mcp"}
      """;

  // --- status codes ------------------------------------------------------------------

  @Test
  void aRefusedDeletionIsReportedWithItsOwnReason() throws Exception {
    org.mockito.Mockito.doThrow(
            new ResourceConflictException("an MCP server operation is already in progress"))
        .when(deletion).delete("mcp-1");

    mvc.perform(delete("/api/mcp-servers/mcp-1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("an MCP server operation is already in progress"));
  }

  @Test
  void creatingAManagedServerIsAcceptedAndAnExternalOneIsCreated() throws Exception {
    when(registry.create(org.mockito.ArgumentMatchers.any()))
        .thenReturn(server("mcp-1", "managed", "applying"));

    // 202 + Location: the compose stack is still coming up, so the UI polls
    mvc.perform(post("/api/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/mcp-servers/mcp-1"));

    when(registry.create(org.mockito.ArgumentMatchers.any()))
        .thenReturn(server("mcp-2", "external", "idle"));

    // 201: an external server is a catalog row, there is nothing to wait for
    mvc.perform(post("/api/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/mcp-servers/mcp-2"));
  }

  @Test
  void updatingSignalsWhetherWorkIsStillPending() throws Exception {
    when(registry.update(eq("mcp-1"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(server("mcp-1", "external", "idle"));
    mvc.perform(put("/api/mcp-servers/mcp-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk());

    when(registry.update(eq("mcp-1"), org.mockito.ArgumentMatchers.any()))
        .thenReturn(server("mcp-1", "managed", "applying"));
    mvc.perform(put("/api/mcp-servers/mcp-1").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isAccepted());
  }

  @Test
  void deletingAManagedServerIsAcceptedAndAnExternalOneIsOk() throws Exception {
    when(deletion.delete("mcp-1")).thenReturn(server("mcp-1", "managed", "deleting"));
    mvc.perform(delete("/api/mcp-servers/mcp-1")).andExpect(status().isAccepted());

    when(deletion.delete("mcp-2")).thenReturn(server("mcp-2", "external", "idle"));
    mvc.perform(delete("/api/mcp-servers/mcp-2")).andExpect(status().isOk());
  }

  @Test
  void startStopAndApplyAreAllAcceptedAndCheckIsSynchronous() throws Exception {
    when(registry.start("mcp-1")).thenReturn(server("mcp-1", "managed", "starting"));
    when(registry.stop("mcp-1")).thenReturn(server("mcp-1", "managed", "stopping"));
    when(registry.apply("mcp-1")).thenReturn(server("mcp-1", "managed", "applying"));
    when(registry.check("mcp-1")).thenReturn(server("mcp-1", "managed", "idle"));

    mvc.perform(post("/api/mcp-servers/mcp-1/start")).andExpect(status().isAccepted());
    mvc.perform(post("/api/mcp-servers/mcp-1/stop")).andExpect(status().isAccepted());
    mvc.perform(post("/api/mcp-servers/mcp-1/apply")).andExpect(status().isAccepted());

    // check probes the endpoint inline and answers with the refreshed row, so it is the
    // one lifecycle call that is 200 rather than 202
    mvc.perform(post("/api/mcp-servers/mcp-1/check"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checkStatus").value("ok"))
        .andExpect(jsonPath("$.latencyMs").value(3));
  }

  // --- routing and parameters --------------------------------------------------------

  @Test
  void logsDefaultToTwoHundredLinesAndPassAnExplicitTailThrough() throws Exception {
    when(registry.logs(anyString(), anyInt()))
        .thenReturn(List.of(new LogLineDto(1L, "info", "stdout", "listening")));

    mvc.perform(get("/api/mcp-servers/mcp-1/logs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].msg").value("listening"));
    mvc.perform(get("/api/mcp-servers/mcp-1/logs").param("tail", "50")).andExpect(status().isOk());

    ArgumentCaptor<Integer> tail = ArgumentCaptor.forClass(Integer.class);
    verify(registry, org.mockito.Mockito.times(2)).logs(eq("mcp-1"), tail.capture());
    org.junit.jupiter.api.Assertions.assertEquals(List.of(200, 50), tail.getAllValues());
  }

  @Test
  void theRetainedResourceRoutesDoNotFallThroughToTheServerRoutes() throws Exception {
    when(registry.retainedResources()).thenReturn(
        List.of(new RetainedResourceDto("rr-1", "mcp-1", "files", "dh-local", "volume", "data", 1L)));
    when(registry.retainedResource("rr-1")).thenReturn(
        new RetainedResourceDto("rr-1", "mcp-1", "files", "dh-local", "volume", "data", 1L));

    mvc.perform(get("/api/mcp-servers/retained-resources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("volume"));
    mvc.perform(get("/api/mcp-servers/retained-resources/rr-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("data"));

    mvc.perform(delete("/api/mcp-servers/retained-resources/rr-1"))
        .andExpect(status().isNoContent());

    // DELETE /retained-resources/{id} and DELETE /{id} differ by one path segment, and
    // one of them deletes a catalog entry rather than a leftover volume record
    verify(registry).purgeRetainedResource("rr-1");
    verifyNoInteractions(deletion);
  }

  @Test
  void anUnknownRetainedResourceIsANotFoundAndAnInvalidBodyIsABadRequest() throws Exception {
    when(registry.retainedResource("rr-ghost"))
        .thenThrow(new NoSuchElementException("unknown retained resource: rr-ghost"));
    when(registry.create(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new IllegalArgumentException("name is required"));

    mvc.perform(get("/api/mcp-servers/retained-resources/rr-ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown retained resource: rr-ghost"));

    // the validator, not bean validation, owns this request shape — its rejection still
    // has to reach the client as a 400
    mvc.perform(post("/api/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("name is required"));
  }
}
