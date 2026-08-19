package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.LinkedHashMap;
import java.util.List;
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
  String addMcpServer(String configYaml, String configPath, McpServerDefinition definition) {
    String name = definition.name();
    Map<Object, Object> root = parseForEdit(configYaml, configPath);
    Map<Object, Object> servers = mcpServersForEdit(root);
    Object existing = servers.get(name);
    if (existing != null && !(existing instanceof Map<?, ?>)) {
      throw new ResourceConflictException("refusing to overwrite malformed MCP server: " + name);
    }
    Map<Object, Object> server = asMutableMap(existing);
    applyDefinition(server, definition);
    servers.put(name, server);
    root.put("mcp_servers", servers);
    return yaml().dump(root);
  }

  /** Updates an entry, renaming it when the request carries a different name. */
  String updateMcpServer(
      String configYaml, String configPath, String serverName, McpServerDefinition definition) {
    String currentName = serverName(serverName);
    String newName = definition.name();
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
      throw new ResourceConflictException("refusing to rewrite malformed MCP server: " + currentName);
    }
    Map<Object, Object> server = asMutableMap(existing);
    applyDefinition(server, definition);
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
      throw new ResourceConflictException("refusing to rewrite malformed MCP server: " + name);
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
      throw new ResourceConflictException("refusing to rewrite malformed mcp_servers config");
    }
    return asMutableMap(node);
  }

  /**
   * Maps a validated definition onto the entry's YAML.
   *
   * <p>Only the file's shape is decided here — which keys a transport owns, and therefore
   * which of the other transport's leftovers must be cleared. What a valid definition is
   * belongs to {@link McpServerDefinition}, whose invariants this relies on: a network
   * transport has a url and no command, stdio has a command and no headers.
   */
  private void applyDefinition(Map<Object, Object> server, McpServerDefinition definition) {
    // Persist the explicit value. In particular, SSE cannot be inferred from a
    // URL and previously came back from the API incorrectly as HTTP.
    server.put("transport", definition.transport().wireName());
    if (definition.transport() == McpServerDefinition.Transport.STDIO) {
      server.put("command", definition.command());
      if (definition.args().isEmpty()) server.remove("args");
      else server.put("args", definition.args());
      if (definition.environment() != null) {
        if (definition.environment().isEmpty()) server.remove("env");
        else server.put("env", new LinkedHashMap<>(definition.environment()));
      }
      // These keys belong to network transports. Clearing them avoids reviving
      // stale endpoints or credentials after a later transport change.
      server.remove("url");
      server.remove("headers");
    } else {
      server.put("url", definition.url());
      server.remove("command");
      server.remove("args");
      server.remove("env");
      // An omitted header map means the caller did not edit this advanced
      // field. An explicit empty map clears it without disturbing other,
      // genuinely unmodeled Hermes options.
      if (definition.headers() != null) {
        if (definition.headers().isEmpty()) server.remove("headers");
        else server.put("headers", new LinkedHashMap<>(definition.headers()));
      }
    }

    Boolean enabled = definition.enabled();
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
      throw new ResourceConflictException("refusing to rewrite unparseable " + configPath, e);
    }
    throw new ResourceConflictException("refusing to rewrite unparseable " + configPath);
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

}
