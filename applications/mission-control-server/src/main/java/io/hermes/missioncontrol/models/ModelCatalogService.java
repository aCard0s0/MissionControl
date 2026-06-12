package io.hermes.missioncontrol.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ModelCatalogService {

  private static final Logger log = LoggerFactory.getLogger(ModelCatalogService.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final List<String> OPENAI_EXCLUDED = List.of(
      "embedding", "audio", "realtime", "image", "tts", "whisper", "moderation", "transcribe");

  private final ModelCatalogProperties props;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

  public ModelCatalogService(ModelCatalogProperties props) {
    this.props = props;
  }

  public ModelCatalogDto configured(String provider) {
    String normalized = normalize(provider);
    return new ModelCatalogDto(normalized, configuredModels(normalized), "config");
  }

  /** Live list from the provider API; falls back to the configured list. */
  public ModelCatalogDto live(String provider, String apiKey) {
    String normalized = normalize(provider);
    List<String> configured = configuredModels(normalized);
    try {
      List<String> models = switch (normalized) {
        case "anthropic" -> fetchAnthropic(apiKey);
        case "openai" -> fetchOpenai(apiKey);
        default -> configured;
      };
      return new ModelCatalogDto(normalized, models, "live");
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      log.warn("live model fetch for {} failed: {}", normalized, e.toString());
      return new ModelCatalogDto(normalized, configured, "config");
    }
  }

  private List<String> configuredModels(String provider) {
    String csv = switch (provider) {
      case "anthropic" -> props.anthropic();
      case "openai" -> props.openai();
      default -> throw new NoSuchElementException("unknown model provider: " + provider);
    };
    List<String> models = new ArrayList<>();
    for (String entry : (csv == null ? "" : csv).split(",")) {
      String trimmed = entry.trim();
      if (!trimmed.isEmpty()) models.add(trimmed);
    }
    return models;
  }

  private List<String> fetchAnthropic(String apiKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/models"))
        .timeout(TIMEOUT)
        .header("x-api-key", apiKey)
        .header("anthropic-version", "2023-06-01")
        .GET()
        .build();
    return modelIds(send(request), id -> true);
  }

  private List<String> fetchOpenai(String apiKey) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/models"))
        .timeout(TIMEOUT)
        .header("Authorization", "Bearer " + apiKey)
        .GET()
        .build();
    return modelIds(send(request), ModelCatalogService::isOpenaiChatModel);
  }

  private String send(HttpRequest request) throws Exception {
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("provider returned HTTP " + response.statusCode());
    }
    return response.body();
  }

  private List<String> modelIds(String body, Predicate<String> filter) throws Exception {
    JsonNode root = objectMapper.readTree(body);
    List<String> models = new ArrayList<>();
    for (JsonNode entry : root.path("data")) {
      String id = entry.path("id").asText("");
      if (!id.isBlank() && filter.test(id)) models.add(id);
    }
    models.sort(Comparator.reverseOrder());
    return models;
  }

  /** Chat-capable families only: gpt-* and o1/o3/... reasoning models. */
  private static boolean isOpenaiChatModel(String id) {
    String lower = id.toLowerCase(Locale.ROOT);
    if (!lower.startsWith("gpt-") && !lower.matches("o\\d.*")) return false;
    return OPENAI_EXCLUDED.stream().noneMatch(lower::contains);
  }

  private String normalize(String provider) {
    return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
  }
}
