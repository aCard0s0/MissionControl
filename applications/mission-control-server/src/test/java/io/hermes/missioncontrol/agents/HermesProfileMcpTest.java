package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The {@code mcp_servers} block of a profile config, and the probe cache the dashboard
 * reads status from.
 *
 * <p>The cache is the reason these live together: a listing must never report a
 * connection that belongs to a definition since edited, so every config write has to
 * invalidate it. The tests that only reshape YAML drive {@link HermesConfigEditor}
 * directly — no container needed.
 */
class HermesProfileMcpTest {

  private static final HermesConfigEditor EDITOR = new HermesConfigEditor();

  /** Listing only reads the map it is handed, so no exec seam is required. */
  private final HermesProfileMcp mcp = new HermesProfileMcp(null, EDITOR);

  private static HermesProfileMcp liveMcp(DockerExecService dockerExec) {
    return new HermesProfileMcp(new HermesContainerFiles(dockerExec), new HermesConfigEditor());
  }

  @Test
  void mcpStartsUnknownCachesHandshakeFailureAndInvalidatesOnConfigChange() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfileMcp liveMcp = liveMcp(dockerExec);
    Map<String, Object> server = new LinkedHashMap<>(Map.of(
        "url", "http://host.docker.internal:8050/mcp/sse",
        "transport", "sse",
        "enabled", true));
    Map<String, Object> config = Map.of("mcp_servers", Map.of("tp", server));
    String yaml = "mcp_servers:\n  tp:\n    url: http://host.docker.internal:8050/mcp/sse\n    transport: sse\n    enabled: true\n";
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, yaml, ""))
        // Hermes currently exits 0 even when its protocol handshake fails.
        .thenReturn(new DockerExecService.ExecResult(0, "✗ Connection failed", ""));

    assertEquals("unknown", liveMcp.list("unix:///sock", "cid", "ops", config).get(0).status());
    assertEquals("error", liveMcp.test("unix:///sock", "cid", "ops", "tp").status());
    assertEquals("error", liveMcp.list("unix:///sock", "cid", "ops", config).get(0).status());

    server.put("url", "http://host.docker.internal:9999/mcp");
    assertEquals("unknown", liveMcp.list("unix:///sock", "cid", "ops", config).get(0).status());
  }

  @Test
  void disabledMcpIsNeverReportedConnected() {
    Map<String, Object> config = Map.of("mcp_servers", Map.of("off", Map.of(
        "url", "http://example.test/mcp", "enabled", false)));
    AgentMcpServerDto result = mcp.list("unix:///sock", "cid", "ops", config).get(0);
    assertFalse(result.enabled());
    assertEquals("disabled", result.status());
  }

  @Test
  void disablingAndReenablingMcpPreservesConnectionAndUnmodeledConfig() {
    String original = """
        telemetry: false
        mcp_servers:
          reports:
            transport: sse
            url: https://mcp.example.test/sse
            headers:
              Authorization: Bearer stored-token
            tools:
              include: [search, fetch]
            vendor_option: retained
            enabled: true
        """;

    String disabled = EDITOR.setMcpServerEnabled(
        original, "/opt/data/profiles/ops/config.yaml", "reports", false);
    Map<String, Object> disabledServer = mcpServer(disabled, "reports");
    assertEquals(false, disabledServer.get("enabled"));
    assertEquals("sse", disabledServer.get("transport"));
    assertEquals("https://mcp.example.test/sse", disabledServer.get("url"));
    assertEquals(Map.of("Authorization", "Bearer stored-token"), disabledServer.get("headers"));
    assertEquals(Map.of("include", List.of("search", "fetch")), disabledServer.get("tools"));
    assertEquals("retained", disabledServer.get("vendor_option"));
    assertEquals(false, yamlMap(disabled).get("telemetry"));

    String reenabled = EDITOR.setMcpServerEnabled(
        disabled, "/opt/data/profiles/ops/config.yaml", "reports", true);
    Map<String, Object> reenabledServer = mcpServer(reenabled, "reports");
    assertEquals(true, reenabledServer.get("enabled"));
    assertEquals("sse", reenabledServer.get("transport"));
    assertEquals("https://mcp.example.test/sse", reenabledServer.get("url"));
    assertEquals(Map.of("Authorization", "Bearer stored-token"), reenabledServer.get("headers"));
    assertEquals("retained", reenabledServer.get("vendor_option"));
  }

  @Test
  void sseTransportIsPersistedAndReadBackAsSse() {
    AddMcpServerRequest request = new AddMcpServerRequest(
        "events", "sse", "https://mcp.example.test/sse", null, null, true);
    String config = EDITOR.addMcpServer(
        "model: nous/Hermes-4-405B\n", "/opt/data/config.yaml", request);

    Map<String, Object> root = yamlMap(config);
    assertEquals("sse", mcpServer(config, "events").get("transport"));
    AgentMcpServerDto dto = mcp.list("unix:///sock", "cid", "ops", root).get(0);
    assertEquals("sse", dto.transport());
    assertTrue(dto.enabled());
  }

  @Test
  void atomicUpdateClearsBlankArgsAndTransportSpecificStaleFields() {
    String stdio = """
        mcp_servers:
          tools:
            transport: stdio
            command: uvx
            args: [mcp-server, --verbose]
            enabled: false
            vendor_option: retained
        """;
    String argsCleared = EDITOR.updateMcpServer(
        stdio, "/opt/data/config.yaml", "tools",
        new AddMcpServerRequest("tools", "stdio", null, "uvx", "  ", null));
    Map<String, Object> cleared = mcpServer(argsCleared, "tools");
    assertFalse(cleared.containsKey("args"));
    assertEquals(false, cleared.get("enabled"), "an omitted enabled value preserves current state");
    assertEquals("retained", cleared.get("vendor_option"));

    String switchedToSse = EDITOR.updateMcpServer(
        argsCleared, "/opt/data/config.yaml", "tools",
        new AddMcpServerRequest(
            "tools", "sse", "https://mcp.example.test/sse", null, null, null));
    Map<String, Object> network = mcpServer(switchedToSse, "tools");
    assertEquals("sse", network.get("transport"));
    assertFalse(network.containsKey("command"));
    assertFalse(network.containsKey("args"));
    assertEquals("https://mcp.example.test/sse", network.get("url"));
    assertEquals("retained", network.get("vendor_option"));

    network.put("headers", Map.of("Authorization", "Bearer secret"));
    String networkWithHeader = new Yaml().dump(yamlMapWithServer(switchedToSse, "tools", network));
    String headersCleared = EDITOR.updateMcpServer(
        networkWithHeader, "/opt/data/config.yaml", "tools",
        new AddMcpServerRequest(
            "tools", "sse", "https://mcp.example.test/sse", null, null, null, Map.of()));
    Map<String, Object> clearedNetwork = mcpServer(headersCleared, "tools");
    assertFalse(clearedNetwork.containsKey("headers"), "an explicit empty header map clears headers");
    assertEquals("retained", clearedNetwork.get("vendor_option"));

    // Simulate an HTTP-only header configured outside Mission Control. Moving
    // to stdio must clear both the network endpoint and its credentials.
    String switchedToStdio = EDITOR.updateMcpServer(
        networkWithHeader, "/opt/data/config.yaml", "tools",
        new AddMcpServerRequest("tools", "stdio", null, "node", "server.js", null));
    Map<String, Object> command = mcpServer(switchedToStdio, "tools");
    assertEquals("stdio", command.get("transport"));
    assertEquals("node", command.get("command"));
    assertEquals(List.of("server.js"), command.get("args"));
    assertFalse(command.containsKey("url"));
    assertFalse(command.containsKey("headers"));
  }

  @Test
  void renameIsOneTransformationAndRejectsCollisionsBeforeContainerWrite() {
    String config = """
        mcp_servers:
          old-name:
            transport: http
            url: https://old.example.test/mcp
            vendor_option: retained
          occupied:
            transport: stdio
            command: occupied-command
        """;
    String renamed = EDITOR.updateMcpServer(
        config, "/opt/data/config.yaml", "old-name",
        new AddMcpServerRequest(
            "new-name", "http", "https://new.example.test/mcp", null, null, null));
    Map<String, Object> renamedServers = mcpServers(renamed);
    assertFalse(renamedServers.containsKey("old-name"));
    assertTrue(renamedServers.containsKey("new-name"));
    assertTrue(renamedServers.containsKey("occupied"));
    assertEquals("retained", mcpServer(renamed, "new-name").get("vendor_option"));

    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfileMcp liveMcp = liveMcp(dockerExec);
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, config, ""));

    assertThrows(ResourceConflictException.class, () -> liveMcp.update(
        "unix:///sock", "cid", "ops", "old-name",
        new AddMcpServerRequest(
            "occupied", "http", "https://new.example.test/mcp", null, null, null)));
    // No temp-file write (and no deletion) is attempted after the collision is
    // discovered. Asserted by operation rather than by a total exec count, because the
    // profile-existence guard also reads before the config read.
    verify(dockerExec, never()).runAsUser(
        anyString(), anyString(), anyString(), any(), eq("write MCP configuration"), anyBoolean(),
        anyBoolean(), any(Duration.class));
  }

  @Test
  void mcpToolCountParserUsesLargestDiscoveredCount() {
    assertEquals(12, HermesProfileMcp.parseToolCount("Connected: 3 tools\nTools discovered: 12"));
    assertEquals(true, HermesProfileMcp.mcpProbeSucceeded("  ✓ Connected (25ms)\n  ✓ Tools discovered: 12"));
    assertEquals(false, HermesProfileMcp.mcpProbeSucceeded("  ✗ Connection failed (7000ms)"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yamlMap(String config) {
    return (Map<String, Object>) new Yaml().load(config);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mcpServers(String config) {
    return (Map<String, Object>) yamlMap(config).get("mcp_servers");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mcpServer(String config, String name) {
    return (Map<String, Object>) mcpServers(config).get(name);
  }

  private static Map<String, Object> yamlMapWithServer(
      String config, String name, Map<String, Object> server) {
    Map<String, Object> root = yamlMap(config);
    @SuppressWarnings("unchecked")
    Map<String, Object> servers = (Map<String, Object>) root.get("mcp_servers");
    servers.put(name, server);
    return root;
  }
}
