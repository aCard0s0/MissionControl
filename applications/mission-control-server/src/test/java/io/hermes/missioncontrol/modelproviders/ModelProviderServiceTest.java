package io.hermes.missioncontrol.modelproviders;

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
class ModelProviderServiceTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void trailingSlashesAreStrippedSoOneHostIsOneRow() throws Exception {
    assertEquals("http://box:11434", ModelProviderService.normalizeProviderUrl("http://box:11434"));
    assertEquals("http://box:11434", ModelProviderService.normalizeProviderUrl("http://box:11434/"));
    assertEquals("http://box:11434", ModelProviderService.normalizeProviderUrl("http://box:11434///"));
    assertEquals("http://box:11434", ModelProviderService.normalizeProviderUrl("  http://box:11434/  "));
  }

  @Test
  void httpsIsAccepted() {
    assertEquals("https://box:11434", ModelProviderService.normalizeProviderUrl("https://box:11434"));
  }

  @Test
  void aUrlWithoutAnHttpSchemeIsRejectedAsABadRequest() {
    // IllegalArgumentException is what the exception handler maps to 400
    assertThrows(IllegalArgumentException.class,
        () -> ModelProviderService.normalizeProviderUrl("box:11434"));
    assertThrows(IllegalArgumentException.class,
        () -> ModelProviderService.normalizeProviderUrl("tcp://box:11434"));
    assertThrows(IllegalArgumentException.class,
        () -> ModelProviderService.normalizeProviderUrl("http://"));
  }

  @Test
  void aFullTagsResponseIsParsed() throws Exception {
    List<OllamaModelDto> models = ModelProviderService.parseTags(JSON.readTree("""
        {"models":[
          {"name":"llama3:8b","size":4661224676,"modified_at":"2026-05-01T10:15:30Z",
           "details":{"family":"llama","parameter_size":"8B"}}
        ]}
        """));

    OllamaModelDto model = models.getFirst();
    assertEquals("llama3:8b", model.name());
    assertEquals(4661224676L, model.sizeBytes());
    assertEquals("llama", model.family());
    assertEquals("8B", model.parameterSize());
    assertEquals(1_777_630_530_000L, model.modifiedAt());
  }

  @Test
  void everyFieldButTheNameIsOptional() throws Exception {
    List<OllamaModelDto> models = ModelProviderService.parseTags(JSON.readTree("""
        {"models":[{"name":"bare:latest"}]}
        """));

    OllamaModelDto model = models.getFirst();
    assertEquals("bare:latest", model.name());
    assertNull(model.sizeBytes());
    assertNull(model.family());
    assertNull(model.parameterSize());
    assertNull(model.modifiedAt());
  }

  @Test
  void anEmptyOrAbsentModelsArrayYieldsNoModels() throws Exception {
    assertTrue(ModelProviderService.parseTags(JSON.readTree("{\"models\":[]}")).isEmpty());
    assertTrue(ModelProviderService.parseTags(JSON.readTree("{}")).isEmpty());
  }

  @Test
  void anUnparseableTimestampDoesNotSinkTheWholeModel() throws Exception {
    List<OllamaModelDto> models = ModelProviderService.parseTags(JSON.readTree("""
        {"models":[{"name":"llama3:8b","modified_at":"not a date"}]}
        """));

    assertEquals("llama3:8b", models.getFirst().name());
    assertNull(models.getFirst().modifiedAt());
  }

  @Test
  void timestampsConvertToEpochMillis() {
    assertEquals(1_777_630_530_000L, ModelProviderService.epochMs("2026-05-01T10:15:30Z"));
    assertNull(ModelProviderService.epochMs(null));
    assertNull(ModelProviderService.epochMs(""));
    assertNull(ModelProviderService.epochMs("garbage"));
  }
}
