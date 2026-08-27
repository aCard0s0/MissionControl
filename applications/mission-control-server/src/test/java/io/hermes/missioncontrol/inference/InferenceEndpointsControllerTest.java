package io.hermes.missioncontrol.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.hermes.missioncontrol.errors.ApiExceptionHandler;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The ollama-provider endpoints: trimming, validation, and the two delete routes. */
class InferenceEndpointsControllerTest {

  private InferenceEndpointService providers;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    providers = mock(InferenceEndpointService.class);
    mvc = MockMvcBuilders
        .standaloneSetup(new InferenceEndpointsController(providers))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  private static InferenceEndpointDto provider() {
    return new InferenceEndpointDto("mp-1", "workstation", "http://10.0.0.9:11434", "ollama",
        "connected", "0.5.7", null, true);
  }

  @Test
  void addTrimsTheNameAndUrlBeforeReachingTheService() throws Exception {
    when(providers.add(any(), anyString())).thenReturn(provider());

    mvc.perform(post("/api/model-providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  workstation  \",\"url\":\"  http://10.0.0.9:11434  \"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("mp-1"));

    // an untrimmed url would fail normalization and store a duplicate of an existing host
    verify(providers).add("workstation", "http://10.0.0.9:11434");
  }

  @Test
  void aBlankNameOrUrlIsRejectedBeforeTheService() throws Exception {
    mvc.perform(post("/api/model-providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"   \",\"url\":\"http://10.0.0.9:11434\"}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/api/model-providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"x\",\"url\":\"\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(providers);
  }

  @Test
  void pullIsAcceptedAndTheModelNameIsTrimmed() throws Exception {
    mvc.perform(post("/api/model-providers/mp-1/models/pull")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  qwen3:8b  \"}"))
        // 202: the pull runs in the background, the response does not wait for it
        .andExpect(status().isAccepted());

    ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
    verify(providers).pull(eq("mp-1"), model.capture());
    assertEquals("qwen3:8b", model.getValue());
  }

  @Test
  void aBlankModelNameIsRejectedOnEveryModelRoute() throws Exception {
    for (String route : List.of("pull", "delete", "load", "unload")) {
      mvc.perform(post("/api/model-providers/mp-1/models/" + route)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"name\":\"  \"}"))
          .andExpect(status().isBadRequest());
    }

    verifyNoInteractions(providers);
  }

  @Test
  void startAndStopTrimTheModelNameAndAnswerOnlyOnceTheServerHas() throws Exception {
    mvc.perform(post("/api/model-providers/mp-1/models/load")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"  qwen3:8b  \"}"))
        // 200 rather than the pull's 202: a load is worth waiting for, because the operator
        // pressed start and an accepted-but-failed load looks identical to a slow one
        .andExpect(status().isOk());

    mvc.perform(post("/api/model-providers/mp-1/models/unload")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"qwen3:8b\"}"))
        .andExpect(status().isOk());

    verify(providers).load("mp-1", "qwen3:8b");
    verify(providers).unload("mp-1", "qwen3:8b");
  }

  @Test
  void runningReportsWhatTheEndpointHoldsInMemory() throws Exception {
    when(providers.running("mp-1"))
        .thenReturn(List.of(new RunningModelDto("qwen3:8b", 5_100_000_000L)));

    mvc.perform(get("/api/model-providers/mp-1/running"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("qwen3:8b"))
        .andExpect(jsonPath("$[0].sizeVramBytes").value(5_100_000_000L));
  }

  @Test
  void anUnknownProviderIsANotFoundAndAnUnreachableOneIsServiceUnavailable() throws Exception {
    when(providers.models("mp-ghost")).thenThrow(new NoSuchElementException("unknown model provider: mp-ghost"));
    when(providers.models("mp-down")).thenThrow(new UpstreamUnavailableException("ollama not reachable"));

    mvc.perform(get("/api/model-providers/mp-ghost/models"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("unknown model provider: mp-ghost"));

    mvc.perform(get("/api/model-providers/mp-down/models"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("ollama not reachable"));
  }

  @Test
  void modelsAndPullsAreReadOnlyAndReturnTheServiceLists() throws Exception {
    when(providers.models("mp-1"))
        .thenReturn(List.of(new EndpointModelDto("qwen3:8b", 5_100_000_000L, "qwen3", "8B", 99L)));
    when(providers.pulls("mp-1")).thenReturn(List.of(new PullStatusDto("qwen3:8b", "done", null)));

    mvc.perform(get("/api/model-providers/mp-1/models"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("qwen3:8b"))
        .andExpect(jsonPath("$[0].parameterSize").value("8B"));

    mvc.perform(get("/api/model-providers/mp-1/pulls"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("done"));
  }

  @Test
  void deletingAModelIsNotConfusedWithDeletingTheProvider() throws Exception {
    // POST /{id}/models/delete and DELETE /{id} are one typo apart and one of them
    // destroys the registration rather than a single model
    mvc.perform(post("/api/model-providers/mp-1/models/delete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"qwen3:8b\"}"))
        .andExpect(status().isOk());

    verify(providers).deleteModel("mp-1", "qwen3:8b");
    verify(providers, never()).delete(anyString());

    mvc.perform(delete("/api/model-providers/mp-1")).andExpect(status().isOk());
    verify(providers).delete("mp-1");
  }

  @Test
  void checkReturnsTheRefreshedEndpointRow() throws Exception {
    when(providers.check("mp-1")).thenReturn(provider());

    mvc.perform(post("/api/model-providers/mp-1/check"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("connected"))
        .andExpect(jsonPath("$.version").value("0.5.7"));
  }
}
