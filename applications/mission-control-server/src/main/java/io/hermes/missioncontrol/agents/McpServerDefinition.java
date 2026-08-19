package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One MCP server as this package writes it: already validated, already normalised.
 *
 * <p>Exists because {@link AddMcpServerRequest} was doing this job. That record is the HTTP
 * request body — {@code @NotBlank}, nullable advanced fields, a transport as free text — and
 * three callers that never serve an HTTP request were building one anyway: the catalog
 * materializer, the template applier, and (through them) the config editor, which did the
 * transport dispatch and the header and environment validation on the wire shape. The record
 * had even grown two extra constructors to make that easier. So the annotations described a
 * contract that only one of the three paths actually ran.
 *
 * <p>The invariants live in the canonical constructor, so no construction path can skip them,
 * and {@link #from} is the one place the wire shape is interpreted. What is deliberately
 * <em>not</em> here is which YAML keys a transport clears — that is the config file's shape
 * and belongs to {@code HermesConfigEditor}.
 *
 * @param name        trimmed, never blank
 * @param transport   which of the three hermes supports
 * @param url         the endpoint, non-blank for a network transport and null for stdio
 * @param command     the executable, non-blank for stdio and null for a network transport
 * @param args        tokenized command arguments; empty means the entry carries none
 * @param enabled     null leaves whatever the config already says
 * @param headers     null means "not edited"; an empty map clears them. Always null for stdio
 * @param environment null means "not edited"; an empty map clears it. Always null for a
 *                    network transport
 */
public record McpServerDefinition(
    String name,
    Transport transport,
    String url,
    String command,
    List<String> args,
    Boolean enabled,
    Map<String, String> headers,
    Map<String, String> environment) {

  /** The transports hermes understands. SSE cannot be inferred from a URL, so it is carried
   *  explicitly — inferring it is what once reported an SSE server as HTTP. */
  public enum Transport {
    STDIO, HTTP, SSE;

    /** The value hermes reads out of {@code config.yaml}. */
    public String wireName() {
      return name().toLowerCase(Locale.ROOT);
    }

    boolean isNetwork() {
      return this != STDIO;
    }

    /** Reads the value hermes stores, or a request body's free-text transport field. */
    public static Transport of(String value) {
      String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
      return switch (normalized) {
        case "stdio" -> STDIO;
        case "http" -> HTTP;
        case "sse" -> SSE;
        default -> throw new IllegalArgumentException("invalid transport");
      };
    }
  }

  public McpServerDefinition {
    name = requireName(name);
    if (transport == null) throw new IllegalArgumentException("invalid transport");
    args = args == null ? List.of() : List.copyOf(args);
    if (transport.isNetwork()) {
      if (url == null || url.isBlank()) throw new IllegalArgumentException("missing url");
      url = url.trim();
      command = null;
      args = List.of();
      environment = null;
      headers = headers == null ? null : validatedHeaders(headers);
    } else {
      if (command == null || command.isBlank()) throw new IllegalArgumentException("missing command");
      command = command.trim();
      url = null;
      headers = null;
      environment = environment == null ? null : validatedEnvironment(environment);
    }
  }

  /** Interprets a request body: the one place the wire shape is read. */
  public static McpServerDefinition from(AddMcpServerRequest request) {
    if (request == null) throw new IllegalArgumentException("request body is required");
    Transport transport = Transport.of(request.transport());
    return new McpServerDefinition(
        request.name(),
        transport,
        request.url(),
        request.command(),
        transport.isNetwork() ? List.of() : splitArgs(request.args()),
        request.enabled(),
        request.headers(),
        request.environment());
  }

  static String requireName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing server name");
    return value.trim();
  }

  /**
   * Shell-style tokenizer so quoted MCP args keep their internal spaces.
   *
   * <p>Public because a stored template snapshot holds args in the same single-string form a
   * request body does, so it needs the same reading of them.
   */
  public static List<String> splitArgs(String args) {
    if (args == null || args.isBlank()) return List.of();
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    String trimmed = args.trim();
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (quote != 0) {
        if (c == quote) quote = 0; else cur.append(c);
      } else if (c == '\'' || c == '"') {
        quote = c;
      } else if (Character.isWhitespace(c)) {
        if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
      } else {
        cur.append(c);
      }
    }
    if (cur.length() > 0) out.add(cur.toString());
    return List.copyOf(out);
  }

  private static Map<String, String> validatedHeaders(Map<String, String> input) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String name = entry.getKey() == null ? "" : entry.getKey().trim();
      String value = entry.getValue();
      if (name.isBlank()) throw new IllegalArgumentException("MCP header name must not be blank");
      if (value == null) throw new IllegalArgumentException("missing value for MCP header: " + name);
      if (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
          || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("MCP headers must not contain line breaks");
      }
      result.put(name, value);
    }
    return Map.copyOf(result);
  }

  private static Map<String, String> validatedEnvironment(Map<String, String> input) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String name = entry.getKey() == null ? "" : entry.getKey().trim();
      String value = entry.getValue();
      if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
        throw new IllegalArgumentException("invalid MCP environment key: " + name);
      }
      if (value == null) {
        throw new IllegalArgumentException("missing value for MCP environment: " + name);
      }
      if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
        throw new IllegalArgumentException(
            "MCP environment values must not contain NUL or line breaks");
      }
      result.put(name, value);
    }
    return Map.copyOf(result);
  }
}
