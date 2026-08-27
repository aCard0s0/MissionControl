package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Ollama's own protocol: {@code /api/version}, {@code /api/tags}, {@code /api/ps},
 * {@code /api/pull}, {@code /api/delete} and the load/unload corner of {@code /api/generate}.
 * The only kind that can manage models.
 *
 * <p>{@code @Order(1)} is load-bearing, not cosmetic. Ollama serves an OpenAI-compatible
 * {@code /v1} <em>as well as</em> this, so a detector that asked {@link OpenAiCompatClient}
 * first would match every ollama server and silently strip its pull and delete. Ollama's own
 * endpoint is the discriminator, so it has to be asked first.
 */
@Component
@Order(1)
public class OllamaProtocolClient extends EndpointClient {

  /** A load reads the weights off disk first; bounded so a wedged server frees the thread. */
  private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(3);

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
    HttpResponse<String> response = send(request(baseUrl + "/api/version", PROBE_TIMEOUT));
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException("ollama returned HTTP " + response.statusCode());
    }
    return objectMapper.readTree(response.body()).path("version").asText(null);
  }

  @Override
  public List<EndpointModelDto> models(String baseUrl) {
    String body = call(request(baseUrl + "/api/tags", CALL_TIMEOUT));
    try {
      return parseTags(objectMapper.readTree(body));
    } catch (Exception e) {
      throw new UpstreamUnavailableException("unexpected response from ollama /api/tags");
    }
  }

  @Override
  public List<RunningModelDto> running(String baseUrl) {
    String body = call(request(baseUrl + "/api/ps", CALL_TIMEOUT));
    try {
      return parsePs(objectMapper.readTree(body));
    } catch (Exception e) {
      throw new UpstreamUnavailableException("unexpected response from ollama /api/ps");
    }
  }

  /**
   * Loads with {@code keep_alive: -1}, which pins the model until something unloads it.
   *
   * <p>Deliberately not ollama's default five minutes: this is a button an operator pressed,
   * and a start that quietly wears off while they are watching the row reads as a bug. The
   * stop button is the other half of that bargain.
   */
  @Override
  public void load(String baseUrl, String model) {
    keepAlive(baseUrl, model, -1, LOAD_TIMEOUT);
  }

  @Override
  public void unload(String baseUrl, String model) {
    keepAlive(baseUrl, model, 0, CALL_TIMEOUT);
  }

  /**
   * A generate with no prompt, which ollama treats as load-or-unload-only: {@code keep_alive}
   * seconds decides which, {@code -1} being forever and {@code 0} being immediately.
   */
  private void keepAlive(String baseUrl, String model, int seconds, Duration timeout) {
    call(HttpRequest.newBuilder(URI.create(baseUrl + "/api/generate"))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(BodyPublishers.ofString(objectMapper.createObjectNode()
            .put("model", model).put("keep_alive", seconds).put("stream", false).toString()))
        .build());
  }

  /**
   * Pulls one model, blocking until the server is done and reporting progress as it goes.
   *
   * <p>Streamed, which is the only way to know a pull is progressing: an unstreamed pull is
   * one silent connection for however many minutes several gigabytes take, and the dashboard
   * could say nothing but "pulling" for all of it.
   *
   * <p>Deliberately no read timeout — a pull runs for minutes. The caller runs this off the
   * request thread and reports progress through its own pull state.
   */
  @Override
  public void pull(String baseUrl, String model, Consumer<String> progress) throws Exception {
    String body = objectMapper.writeValueAsString(Map.of("model", model, "stream", true));
    HttpResponse<Stream<String>> response =
        sendLines(HttpRequest.newBuilder(URI.create(baseUrl + "/api/pull"))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(body))
            .build());
    try (Stream<String> lines = response.body()) {
      if (response.statusCode() != 200) {
        throw new UpstreamUnavailableException(
            brief(lines.findFirst().orElse(null), 200, "request failed"));
      }
      lines.forEach(line -> report(line, progress));
    }
  }

  /**
   * One line of the pull stream.
   *
   * <p>The error case is the reason this parses at all rather than just counting bytes: a pull
   * that has begun streaming is a 200 whatever happens next, so a manifest that does not exist
   * arrives as {@code {"error": …}} in the body. Ignored, the chip would sit on "pulling"
   * forever instead of turning red with the reason.
   */
  private void report(String line, Consumer<String> progress) {
    JsonNode node;
    try {
      node = objectMapper.readTree(line);
    } catch (Exception ignored) {
      return;   // a blank line between objects — the next one carries the state
    }
    if (node.hasNonNull("error")) {
      throw new UpstreamUnavailableException(
          brief(node.path("error").asText(), 200, "pull failed"));
    }
    long total = node.path("total").asLong(0);
    String status = node.path("status").asText("");
    progress.accept(total > 0
        ? node.path("completed").asLong(0) * 100 / total + "% · " + status
        : status);
  }

  @Override
  public void deleteModel(String baseUrl, String model) {
    call(HttpRequest.newBuilder(URI.create(baseUrl + "/api/delete"))
        .timeout(CALL_TIMEOUT)
        .header("Content-Type", "application/json")
        // createObjectNode().toString() rather than writeValueAsString: same {"model":"…"},
        // without the checked exception that would have to be caught and rethrown here
        .method("DELETE",
            BodyPublishers.ofString(objectMapper.createObjectNode().put("model", model).toString()))
        .build());
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

  /** Ollama's {@code /api/ps} body — what it is holding in memory, and what that costs. */
  static List<RunningModelDto> parsePs(JsonNode body) {
    List<RunningModelDto> running = new ArrayList<>();
    for (JsonNode node : body.path("models")) {
      running.add(new RunningModelDto(
          node.path("name").asText(),
          node.has("size_vram") ? node.path("size_vram").asLong() : null));
    }
    return running;
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
