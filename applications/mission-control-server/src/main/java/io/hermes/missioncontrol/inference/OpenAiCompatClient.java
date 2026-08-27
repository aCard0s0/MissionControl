package io.hermes.missioncontrol.inference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The OpenAI-compatible {@code /v1} surface — the one thing every local runtime serves.
 * LM Studio, MLX (mlx-lm.server), vLLM and llama.cpp's server all answer here, and so does
 * ollama, which is why this is {@code @Order(2)}: see the note on {@link OllamaProtocolClient}.
 *
 * <p>Listing only, deliberately. {@code GET /v1/models} is the whole management surface this
 * protocol has: there is no pull and no delete anywhere in it, and models are put on the box
 * out of band (LM Studio's own UI, a downloaded GGUF, a vLLM launch flag). Reporting that
 * honestly through {@link #canManageModels()} is the point — the alternative is a pull button
 * that always fails.
 *
 * <p>What comes back is thin: {@code data[].id} and an optional {@code created}. Parameter
 * size, family and on-disk size are ollama-only, so they stay null and the dashboard drops
 * those columns for this kind.
 */
@Component
@Order(2)
public class OpenAiCompatClient extends EndpointClient {

  public OpenAiCompatClient(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String kind() {
    return "openai";
  }

  /**
   * Always null: the protocol has no version endpoint. {@code GET /v1/models} answering at all
   * is the liveness signal, so this returning normally is what "connected" means here.
   */
  @Override
  public String version(String baseUrl) throws Exception {
    HttpResponse<String> response = get(baseUrl + "/v1/models", PROBE_TIMEOUT);
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException("openai returned HTTP " + response.statusCode());
    }
    return null;
  }

  @Override
  public List<EndpointModelDto> models(String baseUrl) {
    String body = call(() -> get(baseUrl + "/v1/models", CALL_TIMEOUT));
    try {
      return parseModels(objectMapper.readTree(body));
    } catch (Exception e) {
      throw new UpstreamUnavailableException("unexpected response from /v1/models");
    }
  }

  /**
   * OpenAI's {@code {"data":[{"id":…,"created":…}]}}. Only the id is required — LM Studio and
   * llama.cpp both omit {@code created} — and {@code created} is unix <em>seconds</em> where
   * {@link EndpointModelDto#modifiedAt()} is millis.
   */
  static List<EndpointModelDto> parseModels(JsonNode body) {
    List<EndpointModelDto> models = new ArrayList<>();
    for (JsonNode node : body.path("data")) {
      String id = node.path("id").asText(null);
      if (id == null || id.isBlank()) continue;
      Long created = node.has("created") && node.path("created").isNumber()
          ? node.path("created").asLong() * 1000
          : null;
      models.add(new EndpointModelDto(id, null, null, null, created));
    }
    return models;
  }
}
