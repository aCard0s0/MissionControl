package io.hermes.missioncontrol.inference;

import java.util.List;

/**
 * One inference endpoint's wire protocol. Implementations are discovered as beans and keyed by
 * {@link #kind()}, so adding a runtime means adding a class here and one entry to
 * {@code SchemaUpgrades.ENDPOINT_KINDS} — never an edit to {@link InferenceEndpointService}.
 *
 * <p>The two protocols are not equal, and this interface does not pretend otherwise.
 * Ollama has a full management API; the OpenAI-compatible surface every other local runtime
 * serves can only <em>list</em>. Rather than inventing a lowest common denominator, model
 * management is optional and {@link #canManageModels()} says who has it — the dashboard hides
 * what an endpoint cannot do rather than offering a button that fails.
 */
public interface EndpointClient {

  /** The {@code kind} column value this client is selected by. */
  String kind();

  /**
   * The server's reported version, or null when the protocol has no version endpoint.
   *
   * <p>Doubles as the reachability probe: returning normally means connected. Throws raw
   * rather than wrapping, because the caller turns the failure into the operator-facing
   * detail and needs the original exception to tell a refused port from a bad host name.
   */
  String version(String baseUrl) throws Exception;

  /** Models the endpoint reports. Fields it cannot supply come back null. */
  List<EndpointModelDto> models(String baseUrl);

  /** Whether {@link #pull} and {@link #deleteModel} do anything here. */
  default boolean canManageModels() {
    return false;
  }

  /** Throws unless this endpoint can manage models, so callers can refuse early. */
  default void requireModelManagement() {
    if (!canManageModels()) {
      throw unsupported("add or remove models");
    }
  }

  /** Pulls a model, blocking until done. Only called when {@link #canManageModels()}. */
  default void pull(String baseUrl, String model) throws Exception {
    throw unsupported("pull models");
  }

  /** Removes a model. Only called when {@link #canManageModels()}. */
  default void deleteModel(String baseUrl, String model) {
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
}
