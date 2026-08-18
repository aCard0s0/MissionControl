package io.hermes.missioncontrol.errors;

/**
 * Something Mission Control depends on — a Docker daemon, the MCP registry, an agent
 * container — is not reachable right now. The request was well-formed and retrying later
 * may succeed, so this maps to 503.
 *
 * <p>Distinct from an unexpected {@link RuntimeException}, which means Mission Control
 * itself is broken and maps to 500.
 */
public class UpstreamUnavailableException extends RuntimeException {

  public UpstreamUnavailableException(String message) {
    super(message);
  }

  public UpstreamUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
