package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import io.hermes.missioncontrol.errors.ConnectionFailure;
import io.hermes.missioncontrol.inference.InferenceEndpointRepository.EndpointRow;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

/**
 * The registry of inference endpoints — self-hosted model servers Mission Control can
 * administer, whether that is ollama on this machine, on a Mac across the LAN, or on a
 * rented box.
 *
 * <p>Protocol-agnostic on purpose: registration, url normalisation, the probe cache and
 * the pull-state map are the same whatever answers at the other end. The wire format lives
 * in {@link OllamaProtocolClient}, the one ollama-aware class here, so adding a second
 * {@code kind} means adding a client beside it rather than editing this.
 *
 * <p>Distinct from {@code agents.ModelProviderRegistry}, which is the list of model
 * <em>vendors</em> (Anthropic, DeepSeek, Ollama Cloud) and their API-key env vars. That is a
 * capability description; this is a URL you run.
 */
@Service
public class InferenceEndpointService {

  private static final Logger log = LoggerFactory.getLogger(InferenceEndpointService.class);

  private static final long PROBE_TTL_MS = 10_000;

  private record Probe(String status, String version, String detail, long at) {}

  private final InferenceEndpointRepository repository;
  /** kind -> client, in detection order. See {@link #detectKind}. */
  private final Map<String, EndpointClient> clients;
  private final Map<String, Probe> probeCache = new ConcurrentHashMap<>();
  // endpointId -> model -> status; pulls survive only for the lifetime of the process
  private final Map<String, Map<String, PullStatusDto>> pullState = new ConcurrentHashMap<>();
  private final ExecutorService pullExecutor = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "model-pull");
    thread.setDaemon(true);
    return thread;
  });

  /**
   * Clients arrive as beans and are keyed by kind. Ordered ollama-first, which
   * {@link #detectKind} depends on — see the note there.
   */
  public InferenceEndpointService(InferenceEndpointRepository repository,
      List<EndpointClient> clients) {
    this.repository = repository;
    this.clients = clients.stream()
        .sorted(Comparator.comparingInt(c -> OllamaProtocolClient.KIND.equals(c.kind()) ? 0 : 1))
        .collect(LinkedHashMap::new, (m, c) -> m.put(c.kind(), c), LinkedHashMap::putAll);
  }

  /**
   * The client for a stored row.
   *
   * <p>Throws rather than falling back: a row whose kind has no client means a downgrade left
   * data the running build cannot serve, and quietly treating it as ollama would fire ollama
   * calls at something that is not ollama.
   */
  private EndpointClient clientFor(EndpointRow row) {
    EndpointClient client = clients.get(row.kind());
    if (client == null) {
      throw new IllegalStateException(
          "endpoint " + row.id() + " has kind '" + row.kind() + "', which this build cannot serve");
    }
    return client;
  }

  /**
   * Which protocol answers at a url, decided by asking.
   *
   * <p>Order is load-bearing: ollama serves an OpenAI-compatible {@code /v1} <em>as well as</em>
   * its own API, so probing {@code /v1/models} first would label every ollama server "openai"
   * and silently strip its pull and delete. Ollama's own endpoint is the discriminator, so it
   * has to be asked first — which is what the constructor's ordering guarantees.
   */
  private String detectKind(String url) {
    for (EndpointClient client : clients.values()) {
      try {
        client.version(url);
        return client.kind();
      } catch (Exception ignored) {
        // not this protocol — try the next
      }
    }
    throw new IllegalArgumentException(
        "no model server answered at that url — expected ollama (/api/version) or an "
            + "OpenAI-compatible endpoint (/v1/models)");
  }

  @jakarta.annotation.PreDestroy
  void shutdownPulls() {
    pullExecutor.shutdownNow();
  }

  public List<InferenceEndpointDto> list() {
    return repository.findAll().stream().map(row -> toDto(row, probe(row, false))).toList();
  }

  public InferenceEndpointDto check(String id) {
    EndpointRow row = require(id);
    return toDto(row, probe(row, true));
  }

  public InferenceEndpointDto add(String name, String url) {
    String normalized = normalizeEndpointUrl(url);
    if (repository.urlExists(normalized)) {
      throw new IllegalArgumentException("a provider with this url already exists");
    }
    EndpointRow row = new EndpointRow(
        "mp-" + UUID.randomUUID().toString().substring(0, 8), name, normalized,
        detectKind(normalized));
    repository.insert(row);
    return toDto(row, probe(row, true));
  }

  public void delete(String id) {
    require(id);
    repository.delete(id);
    probeCache.remove(id);
    pullState.remove(id);
  }

  public List<EndpointModelDto> models(String id) {
    EndpointRow row = require(id);
    return clientFor(row).models(row.url());
  }

  /**
   * The endpoint url as stored: scheme-checked and stripped of trailing slashes, so the
   * same host typed two ways is one row rather than two.
   *
   * <p>Keeps throwing {@link IllegalArgumentException} — the exception handler maps that
   * to 400, and a different type here would turn a typo into a server error.
   */
  static String normalizeEndpointUrl(String url) {
    String normalized = url.trim();
    if (!normalized.matches("^https?://.+")) {
      throw new IllegalArgumentException("provider url must look like http://host:port");
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  public void pull(String id, String model) {
    EndpointRow row = require(id);
    // Refuse on the request thread. Submitted first, the same refusal would surface a minute
    // later as an error chip on a pull that never had a chance of starting.
    clientFor(row).requireModelManagement();
    pullsOf(row.id()).put(model, new PullStatusDto(model, "pulling", null));
    pullExecutor.submit(() -> runPull(row, model));
  }

  public List<PullStatusDto> pulls(String id) {
    return List.copyOf(pullsOf(require(id).id()).values());
  }

  public void deleteModel(String id, String model) {
    EndpointRow row = require(id);
    clientFor(row).deleteModel(row.url(), model);
    pullsOf(row.id()).remove(model);
  }

  /** State is reported through /pulls rather than returned: the call outlives the request. */
  private void runPull(EndpointRow row, String model) {
    try {
      clientFor(row).pull(row.url(), model);
      pullsOf(row.id()).put(model, new PullStatusDto(model, "done", null));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      pullsOf(row.id()).put(model, new PullStatusDto(model, "error", "pull interrupted"));
    } catch (Exception e) {
      log.warn("pull of {} from {} failed: {}", model, row.url(), e.toString());
      pullsOf(row.id()).put(model,
          new PullStatusDto(model, "error", brief(e.getMessage(), 200, "request failed")));
    }
  }

  private Map<String, PullStatusDto> pullsOf(String endpointId) {
    return pullState.computeIfAbsent(endpointId, k -> new ConcurrentHashMap<>());
  }

  private EndpointRow require(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown model provider: " + id));
  }

  private Probe probe(EndpointRow row, boolean force) {
    Probe cached = probeCache.get(row.id());
    if (!force && cached != null && System.currentTimeMillis() - cached.at() < PROBE_TTL_MS) {
      return cached;
    }
    Probe fresh;
    try {
      fresh = new Probe("connected", clientFor(row).version(row.url()), null,
          System.currentTimeMillis());
    } catch (Exception e) {
      String reason = ConnectionFailure.describe(e);
      log.warn("probe of {} ({}) failed: {}", row.id(), row.url(), reason);
      fresh = new Probe("error", null,
          row.kind() + " not reachable — check the address and that the server is running ("
              + reason + ")",
          System.currentTimeMillis());
    }
    probeCache.put(row.id(), fresh);
    return fresh;
  }

  private InferenceEndpointDto toDto(EndpointRow row, Probe probe) {
    EndpointClient client = clients.get(row.kind());
    return new InferenceEndpointDto(row.id(), row.name(), row.url(), row.kind(),
        probe.status(), probe.version(), probe.detail(),
        client != null && client.canManageModels());
  }
}
