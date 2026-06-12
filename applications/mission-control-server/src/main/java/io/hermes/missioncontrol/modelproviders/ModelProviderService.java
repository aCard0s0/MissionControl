package io.hermes.missioncontrol.modelproviders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.modelproviders.ModelProviderRepository.ProviderRow;
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
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelProviderService {

  private static final Logger log = LoggerFactory.getLogger(ModelProviderService.class);

  private static final long PROBE_TTL_MS = 10_000;
  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration TAGS_TIMEOUT = Duration.ofSeconds(10);

  private record Probe(String status, String version, String detail, long at) {}

  private final ModelProviderRepository repository;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();
  private final Map<String, Probe> probeCache = new ConcurrentHashMap<>();
  // providerId -> model -> status; pulls survive only for the lifetime of the process
  private final Map<String, Map<String, PullStatusDto>> pullState = new ConcurrentHashMap<>();
  private final ExecutorService pullExecutor = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "ollama-pull");
    thread.setDaemon(true);
    return thread;
  });

  public ModelProviderService(ModelProviderRepository repository) {
    this.repository = repository;
  }

  @jakarta.annotation.PreDestroy
  void shutdownPulls() {
    pullExecutor.shutdownNow();
  }

  public List<ModelProviderDto> list() {
    return repository.findAll().stream().map(row -> toDto(row, probe(row, false))).toList();
  }

  public ModelProviderDto check(String id) {
    ProviderRow row = require(id);
    return toDto(row, probe(row, true));
  }

  public ModelProviderDto add(String name, String url) {
    String normalized = url.trim();
    if (!normalized.matches("^https?://.+")) {
      throw new IllegalArgumentException("provider url must look like http://host:port");
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    if (repository.urlExists(normalized)) {
      throw new IllegalArgumentException("a provider with this url already exists");
    }
    ProviderRow row = new ProviderRow("mp-" + UUID.randomUUID().toString().substring(0, 8), name, normalized, "ollama");
    repository.insert(row);
    return toDto(row, probe(row, true));
  }

  public void delete(String id) {
    require(id);
    repository.delete(id);
    probeCache.remove(id);
    pullState.remove(id);
  }

  public List<OllamaModelDto> models(String id) {
    ProviderRow row = require(id);
    String body = ollama(() -> get(row.url() + "/api/tags", TAGS_TIMEOUT));
    List<OllamaModelDto> models = new ArrayList<>();
    try {
      for (JsonNode node : objectMapper.readTree(body).path("models")) {
        JsonNode details = node.path("details");
        models.add(new OllamaModelDto(
            node.path("name").asText(),
            node.has("size") ? node.path("size").asLong() : null,
            details.path("family").asText(null),
            details.path("parameter_size").asText(null),
            epochMs(node.path("modified_at").asText(null))));
      }
    } catch (Exception e) {
      throw new RuntimeException("unexpected response from ollama /api/tags");
    }
    return models;
  }

  public void pull(String id, String model) {
    ProviderRow row = require(id);
    pullsOf(row.id()).put(model, new PullStatusDto(model, "pulling", null));
    pullExecutor.submit(() -> runPull(row, model));
  }

  public List<PullStatusDto> pulls(String id) {
    return List.copyOf(pullsOf(require(id).id()).values());
  }

  public void deleteModel(String id, String model) {
    ProviderRow row = require(id);
    ollama(() -> {
      HttpRequest request = HttpRequest.newBuilder(URI.create(row.url() + "/api/delete"))
          .timeout(TAGS_TIMEOUT)
          .header("Content-Type", "application/json")
          .method("DELETE", BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of("model", model))))
          .build();
      return http.send(request, BodyHandlers.ofString());
    });
    pullsOf(row.id()).remove(model);
  }

  /** No read timeout — pulls take minutes; state is reported through /pulls. */
  private void runPull(ProviderRow row, String model) {
    try {
      String body = objectMapper.writeValueAsString(Map.of("model", model, "stream", false));
      HttpRequest request = HttpRequest.newBuilder(URI.create(row.url() + "/api/pull"))
          .header("Content-Type", "application/json")
          .POST(BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = http.send(request, BodyHandlers.ofString());
      if (response.statusCode() == 200) {
        pullsOf(row.id()).put(model, new PullStatusDto(model, "done", null));
      } else {
        pullsOf(row.id()).put(model, new PullStatusDto(model, "error", brief(response.body())));
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pullsOf(row.id()).put(model, new PullStatusDto(model, "error", "pull interrupted"));
    } catch (Exception e) {
      log.warn("pull of {} from {} failed: {}", model, row.url(), e.toString());
      pullsOf(row.id()).put(model, new PullStatusDto(model, "error", brief(e.getMessage())));
    }
  }

  private Map<String, PullStatusDto> pullsOf(String providerId) {
    return pullState.computeIfAbsent(providerId, k -> new ConcurrentHashMap<>());
  }

  private ProviderRow require(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown model provider: " + id));
  }

  private Probe probe(ProviderRow row, boolean force) {
    Probe cached = probeCache.get(row.id());
    if (!force && cached != null && System.currentTimeMillis() - cached.at() < PROBE_TTL_MS) {
      return cached;
    }
    Probe fresh;
    try {
      HttpResponse<String> response = get(row.url() + "/api/version", PROBE_TIMEOUT);
      if (response.statusCode() != 200) {
        throw new RuntimeException("ollama returned HTTP " + response.statusCode());
      }
      JsonNode body = objectMapper.readTree(response.body());
      fresh = new Probe("connected", body.path("version").asText(null), null, System.currentTimeMillis());
    } catch (Exception e) {
      log.warn("probe of {} ({}) failed: {}", row.id(), row.url(), e.toString());
      fresh = new Probe("error", null,
          "ollama not reachable — check the address and that the server is running",
          System.currentTimeMillis());
    }
    probeCache.put(row.id(), fresh);
    return fresh;
  }

  private HttpResponse<String> get(String url, Duration timeout) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
    return http.send(request, BodyHandlers.ofString());
  }

  private interface OllamaCall {
    HttpResponse<String> send() throws Exception;
  }

  /** Synchronous ollama calls surface as a short RuntimeException (503 via handler). */
  private String ollama(OllamaCall call) {
    HttpResponse<String> response;
    try {
      response = call.send();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("ollama call interrupted");
    } catch (Exception e) {
      throw new RuntimeException("ollama not reachable: " + brief(e.getMessage()));
    }
    if (response.statusCode() != 200) {
      throw new RuntimeException("ollama returned HTTP " + response.statusCode() + ": " + brief(response.body()));
    }
    return response.body();
  }

  private static String brief(String message) {
    if (message == null || message.isBlank()) return "request failed";
    String firstLine = message.lines().findFirst().orElse(message).trim();
    return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
  }

  private static Long epochMs(String modifiedAt) {
    if (modifiedAt == null || modifiedAt.isBlank()) return null;
    try {
      return OffsetDateTime.parse(modifiedAt).toInstant().toEpochMilli();
    } catch (Exception ignored) {
      return null;
    }
  }

  private static ModelProviderDto toDto(ProviderRow row, Probe probe) {
    return new ModelProviderDto(row.id(), row.name(), row.url(), row.kind(),
        probe.status(), probe.version(), probe.detail());
  }
}
