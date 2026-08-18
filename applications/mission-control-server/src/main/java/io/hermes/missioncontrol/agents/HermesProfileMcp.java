package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The {@code mcp_servers} block of a profile's {@code config.yaml}, plus the probe
 * results the dashboard shows beside each entry.
 *
 * <p>Split out of {@link HermesProfiles} because of the cache: a handshake costs a
 * container exec, so a listing reports the last probe rather than re-running one. That
 * cache is only correct while it is invalidated by the same code that edits the config,
 * which is why both halves live here.
 */
@Component
class HermesProfileMcp {

  private static final Pattern ANSI = Pattern.compile("\\u001B\\[[;\\d]*m");
  private static final Pattern TOOL_COUNT = Pattern.compile("(?i)\\b(\\d+)\\s+tools?\\b");
  private static final Pattern DISCOVERED_TOOL_COUNT =
      Pattern.compile("(?i)tools discovered:\\s*(\\d+)");
  private static final Pattern MCP_CONNECTED = Pattern.compile("(?m)^\\s*[✓✔]\\s+Connected \\(");

  private record CacheKey(String url, String containerId, String profileName, String serverName) {}

  private record CachedProbe(String fingerprint, McpTestResult result) {}

  /** One {@code mcp_servers} entry, as both the listing and the probe need to read it. */
  private record Entry(
      boolean enabled, String transport, String endpoint, String command, String args, int tools) {

    String fingerprint() {
      return Integer.toHexString(Objects.hash(transport, endpoint, command, args, enabled));
    }
  }

  private final HermesContainerFiles files;
  private final HermesConfigEditor config;
  private final ConcurrentMap<CacheKey, CachedProbe> probeCache = new ConcurrentHashMap<>();

  HermesProfileMcp(HermesContainerFiles files, HermesConfigEditor config) {
    this.files = files;
    this.config = config;
  }

  // ── config edits ───────────────────────────────────────────────────────────

  void add(String url, String containerId, String profileName, AddMcpServerRequest request) {
    String name = config.serverName(request.name());
    rewriteConfig(url, containerId, profileName, List.of(name),
        (yaml, path) -> config.addMcpServer(yaml, path, request));
  }

  /** Updates (and optionally renames) an existing MCP entry with a single
   * atomic config-file replacement. A rename collision is rejected before any
   * container write, so the original definition is never lost. */
  void update(
      String url, String containerId, String profileName, String serverName,
      AddMcpServerRequest request) {
    String currentName = config.serverName(serverName);
    String newName = config.serverName(request.name());
    rewriteConfig(url, containerId, profileName, List.of(currentName, newName),
        (yaml, path) -> config.updateMcpServer(yaml, path, currentName, request));
  }

  /** Toggles only {@code enabled}; URL, command, args, headers, tool filters,
   * and any keys unknown to Mission Control are retained in the YAML data model. */
  void setEnabled(
      String url, String containerId, String profileName, String serverName, boolean enabled) {
    String name = config.serverName(serverName);
    rewriteConfig(url, containerId, profileName, List.of(name),
        (yaml, path) -> config.setMcpServerEnabled(yaml, path, name, enabled));
  }

  void remove(String url, String containerId, String profileName, String serverName) {
    String name = config.serverName(serverName);
    rewriteConfig(url, containerId, profileName, List.of(name),
        (yaml, path) -> config.removeMcpServer(yaml, path, name));
  }

  /** The one shape every edit takes: read, rewrite, drop the stale probes, write atomically.
   *  The cache is cleared before the write so a concurrent listing cannot re-cache a probe
   *  taken against the definition that is being replaced. */
  private void rewriteConfig(
      String url, String containerId, String profileName, List<String> invalidate,
      ConfigRewrite rewrite) {
    String configPath = files.requireProfileDir(url, containerId, profileName) + "/config.yaml";
    String configYaml = files.readFile(url, containerId, configPath);
    String updated = rewrite.apply(configYaml, configPath);
    for (String name : invalidate) {
      probeCache.remove(new CacheKey(url, containerId, profileName, name));
    }
    files.writeFileAtomically(url, containerId, configPath, updated);
  }

  @FunctionalInterface
  private interface ConfigRewrite {
    String apply(String configYaml, String configPath);
  }

  // ── listing ────────────────────────────────────────────────────────────────

  List<AgentMcpServerDto> list(
      String hostUrl, String containerId, String profileName, Map<?, ?> configMap) {
    Object mcpServers = configMap == null ? null : configMap.get("mcp_servers");
    if (!(mcpServers instanceof Map<?, ?> serversMap)) return List.of();
    List<AgentMcpServerDto> result = new ArrayList<>();
    for (Map.Entry<?, ?> e : serversMap.entrySet()) {
      String name = YamlValues.stringValue(e.getKey());
      if (name.isBlank()) continue;
      if (!(e.getValue() instanceof Map<?, ?> server)) continue;
      Entry entry = readEntry(server);
      CachedProbe cached = validCache(new CacheKey(hostUrl, containerId, profileName, name), entry);

      String status = !entry.enabled() ? "disabled"
          : cached == null ? "unknown" : cached.result().status();
      result.add(new AgentMcpServerDto(
          name, name, entry.transport(), entry.enabled(), status,
          cached == null ? entry.tools() : cached.result().tools(),
          cached == null ? null : cached.result().latencyMs(),
          cached == null ? null : cached.result().error(),
          cached == null ? null : cached.result().checkedAt(),
          blankToNull(entry.endpoint()), blankToNull(entry.command()), blankToNull(entry.args())));
    }
    return result;
  }

  /** The cached probe for this entry, dropping one taken against a since-edited definition. */
  private CachedProbe validCache(CacheKey key, Entry entry) {
    CachedProbe cached = probeCache.get(key);
    if (cached != null && !cached.fingerprint().equals(entry.fingerprint())) {
      probeCache.remove(key, cached);
      return null;
    }
    return cached;
  }

  private static Entry readEntry(Map<?, ?> server) {
    boolean enabled = !"false".equalsIgnoreCase(YamlValues.stringValue(server.get("enabled")));
    String configuredTransport =
        YamlValues.stringValue(server.get("transport")).toLowerCase(Locale.ROOT);
    String transport = server.containsKey("command") ? "stdio"
        : ("sse".equals(configuredTransport) ? "sse" : "http");
    int tools = 0;
    if (server.get("tools") instanceof Map<?, ?> toolsMap
        && toolsMap.get("include") instanceof List<?> include) {
      tools = (int) include.stream().filter(x -> !YamlValues.stringValue(x).isBlank()).count();
    }
    return new Entry(enabled, transport,
        YamlValues.stringValue(server.get("url")),
        YamlValues.stringValue(server.get("command")),
        YamlValues.joinArgs(server.get("args")),
        tools);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  // ── probing ────────────────────────────────────────────────────────────────

  /** Probes a single MCP server with Hermes' own MCP initialize handshake. */
  McpTestResult test(String url, String containerId, String profileName, String serverName) {
    if (serverName == null || serverName.isBlank()) {
      throw new IllegalArgumentException("missing server name");
    }
    long checkedAt = System.currentTimeMillis();
    Map<?, ?> configMap = YamlValues.parseMap(
        files.readFile(url, containerId, ProfilePaths.configFile(profileName)));
    if (!(configMap.get("mcp_servers") instanceof Map<?, ?> servers)
        || !(servers.get(serverName) instanceof Map<?, ?> server)) {
      return new McpTestResult(serverName, "error", 0, null, "server not found in config.yaml", checkedAt);
    }

    Entry entry = readEntry(server);
    CacheKey cacheKey = new CacheKey(url, containerId, profileName, serverName);
    if (!entry.enabled()) {
      McpTestResult disabled =
          new McpTestResult(serverName, "disabled", entry.tools(), null, null, checkedAt);
      probeCache.put(cacheKey, new CachedProbe(entry.fingerprint(), disabled));
      return disabled;
    }

    long start = System.nanoTime();
    List<String> probeCommand = "default".equals(profileName)
        ? List.of("hermes", "mcp", "test", serverName)
        : List.of("hermes", "-p", profileName, "mcp", "test", serverName);
    ExecResult probe = files.exec(url, containerId, probeCommand, false);
    long latencyMs = (System.nanoTime() - start) / 1_000_000L;
    String probeOutput = probe.stdout() + "\n" + probe.stderr();
    int discoveredTools = Math.max(entry.tools(), parseToolCount(probeOutput));

    McpTestResult result = probe.exitCode() == 0 && mcpProbeSucceeded(probeOutput)
        ? new McpTestResult(serverName, "connected", discoveredTools, latencyMs, null, checkedAt)
        : new McpTestResult(serverName, "error", discoveredTools, null,
            probeError(probe.stdout(), probe.stderr()), checkedAt);
    probeCache.put(cacheKey, new CachedProbe(entry.fingerprint(), result));
    return result;
  }

  static int parseToolCount(String output) {
    String text = output == null ? "" : output;
    Matcher matcher = TOOL_COUNT.matcher(text);
    int count = 0;
    while (matcher.find()) count = Math.max(count, Integer.parseInt(matcher.group(1)));
    matcher = DISCOVERED_TOOL_COUNT.matcher(text);
    while (matcher.find()) count = Math.max(count, Integer.parseInt(matcher.group(1)));
    return count;
  }

  static boolean mcpProbeSucceeded(String output) {
    String clean = ANSI.matcher(output == null ? "" : output).replaceAll("");
    return MCP_CONNECTED.matcher(clean).find();
  }

  private static String probeError(String stdout, String stderr) {
    String text = stderr == null || stderr.isBlank() ? stdout : stderr;
    String clean = ANSI.matcher(text == null ? "" : text).replaceAll("").trim();
    if (clean.isBlank()) return "MCP handshake failed";
    String[] lines = clean.split("\\R");
    String detail = lines[lines.length - 1].trim();
    if (detail.length() > 300) detail = detail.substring(0, 300);
    return detail.isBlank() ? "MCP handshake failed" : detail;
  }
}
