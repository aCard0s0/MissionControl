package io.hermes.missioncontrol.hermes;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record AddMcpServerRequest(
    @NotBlank String name,
    @NotBlank String transport,
    String url,
    String command,
    String args,
    Boolean enabled,
    /** null means "not edited", an empty map explicitly clears headers. */
    Map<String, String> headers,
    /** null means "not edited", an empty map explicitly clears stdio env. */
    Map<String, String> environment) {

  /** Backwards-compatible constructor for templates and existing callers that
   * do not model HTTP headers. */
  public AddMcpServerRequest(
      String name,
      String transport,
      String url,
      String command,
      String args,
      Boolean enabled) {
    this(name, transport, url, command, args, enabled, null, null);
  }

  public AddMcpServerRequest(
      String name,
      String transport,
      String url,
      String command,
      String args,
      Boolean enabled,
      Map<String, String> headers) {
    this(name, transport, url, command, args, enabled, headers, null);
  }
}
