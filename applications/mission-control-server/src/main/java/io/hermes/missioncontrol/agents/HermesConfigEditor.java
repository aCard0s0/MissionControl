package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure transformations over a profile's {@code config.yaml}.
 *
 * <p>Split out of {@link HermesProfiles}, which owns the Docker exec side. Nothing here
 * touches a container, so the rules that decide what a config edit does — transport
 * switching, rename collisions, header and environment validation, refusing to rewrite a
 * file it cannot parse — can be exercised directly.
 *
 * <p>Uses a plain {@code new Yaml()} per call: the same default dumper options
 * {@link HermesProfiles} used, so the on-disk formatting of a rewritten config is
 * unchanged, and no parser state is shared between concurrent edits — {@link Yaml} keeps
 * mutable per-document state and is documented as unsafe for concurrent use.
 */
@Component
class HermesConfigEditor {

  private static Yaml yaml() {
    return new Yaml();
  }

  /** Creates or overwrites one entry under {@code mcp_servers}. */
  String addMcpServer(String configYaml, String configPath, AddMcpServerRequest request) {
    String name = serverName(request.name());
    Map<Object, Object> root = parseForEdit(configYaml, configPath);
    Map<Object, Object> servers = mcpServersForEdit(root);
    Object existing = servers.get(name);
    if (existing != null && !(existing instanceof Map<?, ?>)) {
      throw new IllegalStateException("refusing to overwrite malformed MCP server: " + name);
    }
    Map<Object, Object> server = asMutableMap(existing);
    applyDefinition(server, request);
    servers.put(name, server);
    root.put("mcp_servers", servers);
    return yaml().dump(root);
  }

  /** Updates an entry, renaming it when the request carries a different name. */
  String updateMcpServer(
      String configYaml, String configPath, String serverName, AddMcpServerRequest request) {
    String currentName = serverName(serverName);
    String newName = serverName(request.name());
    Map<Object, Object> root = parseForEdit(configYaml, configPath);
    Map<Object, Object> servers = mcpServersForEdit(root);
    if (!servers.containsKey(currentName)) {
      throw new NoSuchElementException("unknown MCP server: " + currentName);
    }
    if (!currentName.equals(newName) && servers.containsKey(newName)) {
      throw new ResourceConflictException("an MCP server named '" + newName + "' already exists");
    }
    Object existing = servers.get(currentName);
    if (!(existing instanceof Map<?, ?>)) {
      throw new IllegalStateException("refusing to rewrite malformed MCP server: " + currentName);
    }
    Map<Object, Object> server = asMutableMap(existing);
    applyDefinition(server, request);
    if (!currentName.equals(newName)) servers.remove(currentName);
    servers.put(newName, server);
    root.put("mcp_servers", servers);
    return yaml().dump(root);
  }

  /**
   * Toggles only {@code enabled}; URL, command, args, headers, tool filters, and any keys
   * unknown to Mission Control are retained in the YAML data model.
   */
  String setMcpServerEnabled(
      String configYaml, String configPath, String serverName, boolean enabled) {
    String name = serverName(serverName);
    Map<Object, Object> root = parseForEdit(configYaml, configPath);
    Map<Object, Object> servers = mcpServersForEdit(root);
    Object existing = servers.get(name);
    if (!(existing instanceof Map<?, ?>)) {
      if (existing == null) throw new NoSuchElementException("unknown MCP server: " + name);
      throw new IllegalStateException("refusing to rewrite malformed MCP server: " + name);
    }
    Map<Object, Object> server = asMutableMap(existing);
    server.put("enabled", enabled);
    servers.put(name, server);
    root.put("mcp_servers", servers);
    return yaml().dump(root);
  }

  /** Drops an entry. Removing something that was never there is not an error. */
  String removeMcpServer(String configYaml, String configPath, String serverName) {
    String name = serverName(serverName);
    Map<Object, Object> root = parseForEdit(configYaml, configPath);
    Map<Object, Object> servers = mcpServersForEdit(root);
    servers.remove(name);
    root.put("mcp_servers", servers);
    return yaml().dump(root);
  }

  Map<Object, Object> mcpServersForEdit(Map<Object, Object> root) {
    Object node = root.get("mcp_servers");
    if (node != null && !(node instanceof Map<?, ?>)) {
      throw new IllegalStateException("refusing to rewrite malformed mcp_servers config");
    }
    return asMutableMap(node);
  }

  private void applyDefinition(Map<Object, Object> server, AddMcpServerRequest request) {
    String transport = request.transport() == null
        ? ""
        : request.transport().trim().toLowerCase(Locale.ROOT);
    // Persist the explicit value. In particular, SSE cannot be inferred from a
    // URL and previously came back from the API incorrectly as HTTP.
    server.put("transport", transport);
    if ("stdio".equals(transport)) {
      String command = request.command();
      if (command == null || command.isBlank()) throw new IllegalArgumentException("missing command");
      server.put("command", command.trim());
      String args = request.args();
      if (args == null || args.isBlank()) {
        server.remove("args");
      } else {
        server.put("args", splitArgs(args.trim()));
      }
      if (request.environment() != null) {
        Map<String, String> environment = validatedEnvironment(request.environment());
        if (environment.isEmpty()) server.remove("env"); else server.put("env", environment);
      }
      // These keys belong to network transports. Clearing them avoids reviving
      // stale endpoints or credentials after a later transport change.
      server.remove("url");
      server.remove("headers");
    } else if ("http".equals(transport) || "sse".equals(transport)) {
      String urlValue = request.url();
      if (urlValue == null || urlValue.isBlank()) throw new IllegalArgumentException("missing url");
      server.put("url", urlValue.trim());
      server.remove("command");
      server.remove("args");
      server.remove("env");
      // An omitted header map means the caller did not edit this advanced
      // field. An explicit empty map clears it without disturbing other,
      // genuinely unmodeled Hermes options.
      if (request.headers() != null) {
        Map<String, String> headers = validatedHeaders(request.headers());
        if (headers.isEmpty()) server.remove("headers"); else server.put("headers", headers);
      }
    } else {
      throw new IllegalArgumentException("invalid transport");
    }

    Boolean enabled = request.enabled();
    if (enabled != null) {
      server.put("enabled", enabled);
    } else if (!server.containsKey("enabled")) {
      server.put("enabled", true);
    }
  }

  String serverName(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing server name");
    return value.trim();
  }

  private Map<String, String> validatedHeaders(Map<String, String> input) {
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
    return result;
  }

  private Map<String, String> validatedEnvironment(Map<String, String> input) {
    Map<String, String> result = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String name = entry.getKey() == null ? "" : entry.getKey().trim();
      String value = entry.getValue();
      if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) {
        throw new IllegalArgumentException("invalid MCP environment key: " + name);
      }
      if (value == null) throw new IllegalArgumentException("missing value for MCP environment: " + name);
      if (value.indexOf('\0') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
        throw new IllegalArgumentException("MCP environment values must not contain NUL or line breaks");
      }
      result.put(name, value);
    }
    return result;
  }

  /**
   * Read-for-edit variant: a config we cannot parse must abort the edit — falling back to
   * an empty map would rewrite the file and wipe it.
   */
  Map<Object, Object> parseForEdit(String yamlText, String configPath) {
    if (yamlText == null || yamlText.isBlank()) return new LinkedHashMap<>();
    try {
      Object loaded = yaml().load(yamlText);
      if (loaded instanceof Map<?, ?> map) return new LinkedHashMap<>(map);
    } catch (Exception e) {
      throw new IllegalStateException("refusing to rewrite unparseable " + configPath, e);
    }
    throw new IllegalStateException("refusing to rewrite unparseable " + configPath);
  }

  Map<Object, Object> asMutableMap(Object node) {
    if (node instanceof Map<?, ?> m) {
      return new LinkedHashMap<>(m);
    }
    return new LinkedHashMap<>();
  }

  String dump(Map<Object, Object> root) {
    return yaml().dump(root);
  }

  /** Shell-style tokenizer so quoted MCP args keep their internal spaces. */
  List<String> splitArgs(String args) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < args.length(); i++) {
      char c = args.charAt(i);
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
    return out;
  }
}
