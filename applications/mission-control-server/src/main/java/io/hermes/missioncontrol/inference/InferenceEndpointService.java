package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import io.hermes.missioncontrol.errors.ConnectionFailure;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.inference.InferenceEndpointRepository.EndpointRow;
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
 * administer, whether that is ollama on this machine, a Mac across the LAN, or a rented box.
 *
 * <p><b>The protocol is probed, never stored.</b> Which one answers at a url is a property of
 * the server, not of the row, and this service already probes every endpoint on a short cache
 * to report its status — so the client that answered comes back with the probe for free.
 * Storing it would buy nothing and cost three things: a CHECK constraint needing a table
 * rebuild for every new protocol, a value that goes stale the moment someone puts a different
 * server behind the same url, and an add that has to refuse an endpoint which happens to be
 * switched off. Adding a protocol is now one {@link EndpointClient} bean and nothing else.
 *
 * <p>Distinct from {@code agents.ModelProviderRegistry}, which is the list of model
 * <em>vendors</em> (Anthropic, DeepSeek, Ollama Cloud) and their API-key env vars. That is a
 * capability description; this is a URL you run.
 */
@Service
public class InferenceEndpointService {

  private static final Logger log = LoggerFactory.getLogger(InferenceEndpointService.class);

  private static final long PROBE_TTL_MS = 10_000;

  /** {@code client} is null when nothing answered — the endpoint is down or is not one. */
  private record Probe(EndpointClient client, String status, String version, String detail,
      long at) {}

  private final InferenceEndpointRepository repository;
  /** In detection order. Ollama first, and that matters — see {@link OllamaProtocolClient}. */
  private final List<EndpointClient> clients;
  private final Map<String, Probe> probeCache = new ConcurrentHashMap<>();
  // endpointId -> model -> status; pulls survive only for the lifetime of the process
  private final Map<String, Map<String, PullStatusDto>> pullState = new ConcurrentHashMap<>();
  private final ExecutorService pullExecutor = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "model-pull");
    thread.setDaemon(true);
    return thread;
  });

  /** Spring hands these over in {@code @Order}, which is the detection order. */
  public InferenceEndpointService(InferenceEndpointRepository repository,
      List<EndpointClient> clients) {
    this.repository = repository;
    this.clients = List.copyOf(clients);
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

  /**
   * Registers a url. Deliberately does not require the server to be up: an endpoint that is
   * merely switched off is still one you want listed, and it reports {@code error} until it
   * answers — at which point the probe works out what it is.
   */
  public InferenceEndpointDto add(String name, String url) {
    String normalized = normalizeEndpointUrl(url);
    if (repository.urlExists(normalized)) {
      throw new IllegalArgumentException("a provider with this url already exists");
    }
    EndpointRow row =
        new EndpointRow("mp-" + UUID.randomUUID().toString().substring(0, 8), name, normalized);
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
    return clientOf(row).models(row.url());
  }

  /** What the endpoint is holding in memory — empty for a protocol that cannot say. */
  public List<RunningModelDto> running(String id) {
    EndpointRow row = require(id);
    return clientOf(row).running(row.url());
  }

  /** Loads a model and pins it. Blocks: the weights come off disk before ollama answers. */
  public void load(String id, String model) {
    EndpointRow row = require(id);
    clientOf(row).load(row.url(), model);
  }

  public void unload(String id, String model) {
    EndpointRow row = require(id);
    clientOf(row).unload(row.url(), model);
  }

  /**
   * The url as stored: scheme-checked and stripped of trailing slashes, so the same host
   * typed two ways is one row rather than two.
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
    EndpointClient client = clientOf(row);
    client.requireModelManagement();
    pullsOf(row.id()).put(model, new PullStatusDto(model, "pulling", null));
    pullExecutor.submit(() -> runPull(client, row, model));
  }

  public List<PullStatusDto> pulls(String id) {
    return List.copyOf(pullsOf(require(id).id()).values());
  }

  public void deleteModel(String id, String model) {
    EndpointRow row = require(id);
    clientOf(row).deleteModel(row.url(), model);
    pullsOf(row.id()).remove(model);
  }

  /** State is reported through /pulls rather than returned: the call outlives the request. */
  private void runPull(EndpointClient client, EndpointRow row, String model) {
    try {
      // progress lands in `detail`, which the dashboard already renders — a pull reads as
      // "47% · pulling <digest>" rather than sitting on a bare "pulling" for ten minutes
      client.pull(row.url(), model,
          progress -> pullsOf(row.id()).put(model, new PullStatusDto(model, "pulling", progress)));
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

  /** The protocol that last answered here, or a 503 if none does. */
  private EndpointClient clientOf(EndpointRow row) {
    EndpointClient client = probe(row, false).client();
    if (client == null) {
      throw new UpstreamUnavailableException(
          "no model server is answering at " + row.url() + " — nothing to list or manage");
    }
    return client;
  }

  /**
   * Asks each protocol in turn; the first that answers is what this endpoint is.
   *
   * <p>Costs one request for ollama and two for everything else, cached for
   * {@value #PROBE_TTL_MS}ms. The failure reported is the FIRST client's, because that is the
   * one carrying the connect-level reason — a refused port or an unresolvable host fails the
   * same way for every protocol, and saying so is more use than naming the last one tried.
   */
  private Probe probe(EndpointRow row, boolean force) {
    Probe cached = probeCache.get(row.id());
    if (!force && cached != null && System.currentTimeMillis() - cached.at() < PROBE_TTL_MS) {
      return cached;
    }
    Probe fresh = null;
    Exception firstFailure = null;
    for (EndpointClient client : clients) {
      try {
        fresh = new Probe(client, "connected", client.version(row.url()), null,
            System.currentTimeMillis());
        break;
      } catch (Exception e) {
        if (firstFailure == null) firstFailure = e;
      }
    }
    if (fresh == null) {
      String reason = ConnectionFailure.describe(firstFailure);
      log.warn("probe of {} ({}) failed: {}", row.id(), row.url(), reason);
      fresh = new Probe(null, "error", null,
          "no model server answered — check the address and that the server is running ("
              + reason + ")",
          System.currentTimeMillis());
    }
    probeCache.put(row.id(), fresh);
    return fresh;
  }

  private static InferenceEndpointDto toDto(EndpointRow row, Probe probe) {
    return new InferenceEndpointDto(row.id(), row.name(), row.url(),
        probe.client() == null ? null : probe.client().kind(),
        probe.status(), probe.version(), probe.detail(),
        probe.client() != null && probe.client().canManageModels());
  }
}
