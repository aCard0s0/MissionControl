package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ConnectionFailure;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Everything that speaks ollama's wire protocol: {@code /api/version}, {@code /api/tags},
 * {@code /api/pull} and {@code /api/delete}.
 *
 * <p>This is the ONLY ollama-aware class in the package, and keeping it that way is the
 * point of the split. {@link InferenceEndpointService} above it registers endpoints, caches
 * probes and tracks pulls without naming a protocol.
 *
 * <p>Agents never come through here at all: they consume an endpoint over its
 * OpenAI-compatible {@code /v1} surface, which is why LM Studio, MLX, vLLM and llama.cpp
 * already serve them unchanged. What none of those offer is model <em>management</em> —
 * there is no pull or delete outside ollama, and {@code /v1/models} reports an id and
 * nothing else. So a second {@code kind} gets its own client beside this one, with the
 * unsupported operations refused rather than a lowest-common-denominator generalisation
 * of this one.
 *
 * <p>Operator-facing messages here say "ollama" on purpose. They surface verbatim in the
 * dashboard, and naming the actual server is more use than a generic "endpoint".
 */
@Component
public class OllamaProtocolClient implements EndpointClient {

  static final String KIND = "ollama";

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

  private final ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();

  public OllamaProtocolClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public String kind() {
    return KIND;
  }

  /** The only kind with one: /api/pull and /api/delete exist nowhere else. */
  @Override
  public boolean canManageModels() {
    return true;
  }

  /**
   * The server's reported version, or null when it reports none.
   *
   * <p>Throws raw rather than wrapping: the caller turns the failure into the operator-facing
   * probe detail, and it needs the original exception for {@code ConnectionFailure.describe}
   * to tell a refused port from an unresolvable host.
   */
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
    String body = send(() -> get(baseUrl + "/api/tags", CALL_TIMEOUT));
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
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(body))
        .build();
    HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException(brief(response.body(), 200, "request failed"));
    }
  }

  @Override
  public void deleteModel(String baseUrl, String model) {
    send(() -> {
      HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/delete"))
          .timeout(CALL_TIMEOUT)
          .header("Content-Type", "application/json")
          .method("DELETE",
              BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("model", model))))
          .build();
      return http.send(request, BodyHandlers.ofString());
    });
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

  private HttpResponse<String> get(String url, Duration timeout) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    return http.send(request, BodyHandlers.ofString());
  }

  private interface OllamaCall {
    HttpResponse<String> send() throws Exception;
  }

  /** An unreachable or unhappy ollama is a dependency failure, not a Mission Control bug: 503. */
  private String send(OllamaCall call) {
    HttpResponse<String> response;
    try {
      response = call.send();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException("ollama call interrupted");
    } catch (Exception e) {
      throw new UpstreamUnavailableException(
          "ollama not reachable: " + ConnectionFailure.describe(e));
    }
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException("ollama returned HTTP " + response.statusCode()
          + ": " + brief(response.body(), 200, "request failed"));
    }
    return response.body();
  }
}
