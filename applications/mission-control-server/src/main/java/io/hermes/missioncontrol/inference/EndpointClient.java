package io.hermes.missioncontrol.inference;

import static io.hermes.missioncontrol.errors.ApiErrors.brief;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ConnectionFailure;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;

/**
 * One inference endpoint's wire protocol, plus the HTTP plumbing every implementation needs.
 *
 * <p>Implementations are beans; {@link InferenceEndpointService} probes them in
 * {@code @Order} and the first that answers is the endpoint's protocol. Nothing is stored —
 * see the note on that service — so adding a runtime is one class here and nothing else.
 *
 * <p>The protocols are not equal and this type does not pretend otherwise. Ollama has a full
 * management API; the OpenAI-compatible surface every other local runtime serves can only
 * <em>list</em>. Rather than inventing a lowest common denominator, management is optional and
 * {@link #canManageModels()} says who has it — the dashboard hides what an endpoint cannot do
 * rather than offering a button that fails.
 *
 * <p>An abstract class rather than an interface plus a shared base: there is one hierarchy
 * here, and splitting it would be two types where two subclasses already exist.
 */
public abstract class EndpointClient {

  /** Probes are on the critical path of a page render; a slow one must not hold it. */
  protected static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
  protected static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

  protected final ObjectMapper objectMapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();

  protected EndpointClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Names this protocol in operator-facing text and in the API response. */
  public abstract String kind();

  /**
   * The server's reported version, or null when the protocol has no version endpoint.
   *
   * <p>Doubles as the detector: returning normally means this protocol answered here. Throws
   * raw rather than wrapping, because the caller turns the failure into the operator-facing
   * detail and needs the original exception to tell a refused port from a bad host name.
   */
  public abstract String version(String baseUrl) throws Exception;

  /** Models the endpoint reports. Fields it cannot supply come back null. */
  public abstract List<EndpointModelDto> models(String baseUrl);

  /** Whether {@link #pull} and {@link #deleteModel} do anything here. */
  public boolean canManageModels() {
    return false;
  }

  /** Throws unless this endpoint can manage models, so callers can refuse before starting. */
  public void requireModelManagement() {
    if (!canManageModels()) {
      throw unsupported("add or remove models");
    }
  }

  /** Pulls a model, blocking until done. Only called when {@link #canManageModels()}. */
  public void pull(String baseUrl, String model) throws Exception {
    throw unsupported("pull models");
  }

  /** Removes a model. Only called when {@link #canManageModels()}. */
  public void deleteModel(String baseUrl, String model) {
    throw unsupported("delete models");
  }

  /**
   * 400 rather than 501: the dashboard already hides these actions for an endpoint that
   * cannot do them, so reaching here means the request named the wrong endpoint.
   */
  private IllegalArgumentException unsupported(String action) {
    return new IllegalArgumentException(
        "this endpoint (" + kind() + ") cannot " + action + " — only ollama exposes a "
            + "management API; add or remove models on the server itself");
  }

  protected HttpResponse<String> get(String url, Duration timeout) throws Exception {
    return exchange(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build());
  }

  protected HttpResponse<String> exchange(HttpRequest request) throws Exception {
    return http.send(request, BodyHandlers.ofString());
  }

  protected interface Call {
    HttpResponse<String> send() throws Exception;
  }

  /**
   * Runs a call and returns its body, turning anything short of a 200 into a 503.
   *
   * <p>An unreachable or unhappy endpoint is a dependency failure, not a Mission Control bug.
   * Messages name the protocol rather than saying "endpoint", because an operator reading
   * "ollama returned HTTP 500" in the dashboard learns more than one reading "endpoint".
   */
  protected String call(Call call) {
    HttpResponse<String> response;
    try {
      response = call.send();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException(kind() + " call interrupted");
    } catch (Exception e) {
      throw new UpstreamUnavailableException(
          kind() + " not reachable: " + ConnectionFailure.describe(e));
    }
    if (response.statusCode() != 200) {
      throw new UpstreamUnavailableException(kind() + " returned HTTP " + response.statusCode()
          + ": " + brief(response.body(), 200, "request failed"));
    }
    return response.body();
  }
}
