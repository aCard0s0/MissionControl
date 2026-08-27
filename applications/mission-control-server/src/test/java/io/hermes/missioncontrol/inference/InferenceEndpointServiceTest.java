package io.hermes.missioncontrol.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure half of the ollama integration: url normalization and response parsing. Both
 * were previously reachable only through a live ollama server.
 */
class InferenceEndpointServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void trailingSlashesAreStrippedSoOneHostIsOneRow() throws Exception {
    assertEquals("http://box:11434", InferenceEndpointService.normalizeEndpointUrl("http://box:11434"));
    assertEquals("http://box:11434", InferenceEndpointService.normalizeEndpointUrl("http://box:11434/"));
    assertEquals("http://box:11434", InferenceEndpointService.normalizeEndpointUrl("http://box:11434///"));
    assertEquals("http://box:11434", InferenceEndpointService.normalizeEndpointUrl("  http://box:11434/  "));
  }

  @Test
  void httpsIsAccepted() {
    assertEquals("https://box:11434", InferenceEndpointService.normalizeEndpointUrl("https://box:11434"));
  }

  @Test
  void aUrlWithoutAnHttpSchemeIsRejectedAsABadRequest() {
    // IllegalArgumentException is what the exception handler maps to 400
    assertThrows(IllegalArgumentException.class,
        () -> InferenceEndpointService.normalizeEndpointUrl("box:11434"));
    assertThrows(IllegalArgumentException.class,
        () -> InferenceEndpointService.normalizeEndpointUrl("tcp://box:11434"));
    assertThrows(IllegalArgumentException.class,
        () -> InferenceEndpointService.normalizeEndpointUrl("http://"));
  }

  @Test
  void aFullTagsResponseIsParsed() throws Exception {
    List<EndpointModelDto> models = OllamaProtocolClient.parseTags(JSON.readTree("""
        {"models":[
          {"name":"llama3:8b","size":4661224676,"modified_at":"2026-05-01T10:15:30Z",
           "details":{"family":"llama","parameter_size":"8B"}}
        ]}
        """));

    EndpointModelDto model = models.getFirst();
    assertEquals("llama3:8b", model.name());
    assertEquals(4661224676L, model.sizeBytes());
    assertEquals("llama", model.family());
    assertEquals("8B", model.parameterSize());
    assertEquals(1_777_630_530_000L, model.modifiedAt());
  }

  @Test
  void everyFieldButTheNameIsOptional() throws Exception {
    List<EndpointModelDto> models = OllamaProtocolClient.parseTags(JSON.readTree("""
        {"models":[{"name":"bare:latest"}]}
        """));

    EndpointModelDto model = models.getFirst();
    assertEquals("bare:latest", model.name());
    assertNull(model.sizeBytes());
    assertNull(model.family());
    assertNull(model.parameterSize());
    assertNull(model.modifiedAt());
  }

  @Test
  void anEmptyOrAbsentModelsArrayYieldsNoModels() throws Exception {
    assertTrue(OllamaProtocolClient.parseTags(JSON.readTree("{\"models\":[]}")).isEmpty());
    assertTrue(OllamaProtocolClient.parseTags(JSON.readTree("{}")).isEmpty());
  }

  @Test
  void anUnparseableTimestampDoesNotSinkTheWholeModel() throws Exception {
    List<EndpointModelDto> models = OllamaProtocolClient.parseTags(JSON.readTree("""
        {"models":[{"name":"llama3:8b","modified_at":"not a date"}]}
        """));

    assertEquals("llama3:8b", models.getFirst().name());
    assertNull(models.getFirst().modifiedAt());
  }

  @Test
  void aPsResponseSaysWhatIsResidentAndUntilWhen() throws Exception {
    List<RunningModelDto> running = OllamaProtocolClient.parsePs(JSON.readTree("""
        {"models":[{"name":"llama3:8b","size":5000000000,"size_vram":4661224676,
                    "expires_at":"2026-05-01T10:15:30Z"}]}
        """));

    RunningModelDto model = running.getFirst();
    assertEquals("llama3:8b", model.name());
    assertEquals(4661224676L, model.sizeVramBytes());
    assertEquals(1_777_630_530_000L, model.expiresAt());
  }

  @Test
  void aPinnedOrCpuOnlyLoadStillParses() throws Exception {
    // a model pinned with keep_alive -1 reports no usable expiry, and a CPU-only load no
    // VRAM — the row has to render without either rather than dropping the model
    List<RunningModelDto> running = OllamaProtocolClient.parsePs(JSON.readTree("""
        {"models":[{"name":"llama3:8b"}]}
        """));

    assertEquals("llama3:8b", running.getFirst().name());
    assertNull(running.getFirst().sizeVramBytes());
    assertNull(running.getFirst().expiresAt());
    assertTrue(OllamaProtocolClient.parsePs(JSON.readTree("{}")).isEmpty());
  }

  @Test
  void timestampsConvertToEpochMillis() {
    assertEquals(1_777_630_530_000L, OllamaProtocolClient.epochMs("2026-05-01T10:15:30Z"));
    assertNull(OllamaProtocolClient.epochMs(null));
    assertNull(OllamaProtocolClient.epochMs(""));
    assertNull(OllamaProtocolClient.epochMs("garbage"));
  }
}
