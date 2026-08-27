package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ollama's own protocol: {@code /api/version}, {@code /api/tags}, {@code /api/pull} and
 * {@code /api/delete}. The only kind that can manage models.
 *
 * <p>{@code @Order(1)} is load-bearing, not cosmetic. Ollama serves an OpenAI-compatible
 * {@code /v1} <em>as well as</em> this, so a detector that asked {@link OpenAiCompatClient}
 * first would match every ollama server and silently strip its pull and delete. Ollama's own
 * endpoint is the discriminator, so it has to be asked first.
 */
@Component
@Order(1)
public class OllamaProtocolClient extends EndpointClient {

  public OllamaProtocolClient(ObjectMapper objectMapper) {
    super(objectMapper);
  }

  @Override
  public String kind() {
    return "ollama";
  }

  /** The only kind with one: /api/pull and /api/delete exist nowhere else. */
  @Override
  public boolean canManageModels() {
    return true;
  }

  @Override
  public String version(String baseUrl) throws Exception {
    HttpResponse<String> response = get(baseUrl + "/api/version", PROBE_TIMEOUT);
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException("ollama returned HTTP " + response.statusCode());
    }
    return objectMapper.readTree(response.body()).path("version").asText(null);
  }

  @Override
  public List<EndpointModelDto> models(String baseUrl) {
    String body = call(() -> get(baseUrl + "/api/tags", CALL_TIMEOUT));
    try {
      return parseTags(objectMapper.readTree(body));
    } catch (Exception e) {
      throw new UpstreamUnavailableException("unexpected response from ollama /api/tags");
    }
  }

  /**
   * Pulls one model, blocking until the server is done.
   *
   * <p>Deliberately no read timeout — a pull runs for minutes. The caller runs this off the
   * request thread and reports progress through its own pull state.
   */
  @Override
  public void pull(String baseUrl, String model) throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("model", model, "stream", false));
    HttpResponse<String> response = exchange(
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build());
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException(brief(response.body(), 200, "request failed"));
    }
  }

  @Override
  public void deleteModel(String baseUrl, String model) {
    call(() -> exchange(HttpRequest.newBuilder(URI.create(baseUrl + "/api/delete"))
        .timeout(CALL_TIMEOUT)
        .header("Content-Type", "application/json")
        .method("DELETE",
            BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("model", model))))
        .build()));
  }

  /** Ollama's {@code /api/tags} body. Every field but the name is optional. */
  static List<EndpointModelDto> parseTags(JsonNode body) {
    List<EndpointModelDto> models = new ArrayList<>();
    for (JsonNode node : body.path("models")) {
      JsonNode details = node.path("details");
      models.add(new EndpointModelDto(
          node.path("name").asText(),
          node.has("size") ? node.path("size").asLong() : null,
          details.path("family").asText(null),
          details.path("parameter_size").asText(null),
          epochMs(node.path("modified_at").asText(null))));
    }
    return models;
  }

  static Long epochMs(String modifiedAt) {
    if (modifiedAt == null || modifiedAt.isBlank()) return null;
    try {
      return OffsetDateTime.parse(modifiedAt).toInstant().toEpochMilli();
    } catch (Exception ignored) {
      return null;
    }
  }
}
