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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.ConflictException;
import io.hermes.missioncontrol.agents.HermesProfiles.ConfigInfo;
import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.AuxiliaryModelSpec;
import io.hermes.missioncontrol.docker.DockerExecService;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Pure-logic tests for the model-config write planner and the read-back parser —
 * the two seams that carry the OpenRouter-namespace and clone-clearing
 * invariants (both were live-only bugs once, so they get unit coverage here).
 */
class HermesProfilesTest {

  // construction touches no Docker client, so null is safe for the pure methods
  /** Config-file editing needs no container at all now that it lives in its own class. */
  private static final HermesConfigEditor EDITOR = new HermesConfigEditor();

  private final HermesProfiles profiles =
      new HermesProfiles(null, new ObjectMapper(), EDITOR);

  private static Map<String, String> entries(String provider, String model, String baseUrl) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] kv : HermesProfiles.modelConfigEntries(provider, model, baseUrl)) {
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  // ── write planner: modelConfigEntries ──────────────────────────────────────

  @Test
  void standardProviderSetsProviderAndNoBaseUrl() {
    Map<String, String> e = entries("nous", "Hermes-4-405B", null);
    assertEquals("Hermes-4-405B", e.get("model.default"));
    assertEquals("nous", e.get("model.provider"));
    assertEquals(false, e.containsKey("model.base_url"), "standard provider writes no base_url");
  }

  @Test
  void openrouterModelIdKeptVerbatimNotConcatenated() {
    Map<String, String> e = entries("openrouter", "anthropic/claude-sonnet-4", null);
    // the slashed id is written as-is to model.default — never provider/model
    assertEquals("anthropic/claude-sonnet-4", e.get("model.default"));
    assertEquals("openrouter", e.get("model.provider"));
  }

  @Test
  void customEndpointSetsBaseUrlAndNoProvider() {
    Map<String, String> e = entries("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1");
    assertEquals("qwen3:8b", e.get("model.default"));
    assertEquals("http://host.docker.internal:11434/v1", e.get("model.base_url"));
    assertEquals(false, e.containsKey("model.provider"), "custom endpoint writes no provider");
  }

  @Test
  void blankOrAutoProviderWritesNeitherRoutingKey() {
    for (String p : new String[] {null, "", "auto"}) {
      Map<String, String> e = entries(p, "some-model", null);
      assertEquals(false, e.containsKey("model.provider"), "provider=" + p);
      assertEquals(false, e.containsKey("model.base_url"), "provider=" + p);
    }
  }

  @Test
  void everyWriteWipesModelFirstSoCloneCannotLeak() {
    // the clone guard: the FIRST set always resets model to an empty scalar,
    // so no stale key (provider, base_url, api_mode, …) can survive a --clone
    for (String[] c : new String[][] {
        {"nous", "Hermes-4-405B", null},
        {"openrouter", "anthropic/claude-sonnet-4", null},
        {"ollama", "qwen3:8b", "http://x/v1"},
        {"auto", "m", null}}) {
      List<String[]> plan = HermesProfiles.modelConfigEntries(c[0], c[1], c[2]);
      assertEquals("model", plan.get(0)[0], "first set must wipe the model map");
      assertEquals("", plan.get(0)[1]);
      assertEquals("model.default", plan.get(1)[0], "default written right after the wipe");
    }
  }

  // ── write planner: auxiliaryConfigEntries ──────────────────────────────────

  private static Map<String, String> auxEntries(String provider, String model, String baseUrl) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String[] kv : HermesProfiles.auxiliaryConfigEntries(provider, model, baseUrl)) {
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  @Test
  void auxiliaryTasksArePinnedToTheProfilesOwnProviderAndModel() {
    // auto resolves through the main model, so an unpinned task dies with the
    // model map — compression/summarization/memory flush are the visible casualties
    Map<String, String> e = auxEntries("nous", "Hermes-4-405B", null);
    assertEquals("nous", e.get("auxiliary.compression.provider"));
    assertEquals("Hermes-4-405B", e.get("auxiliary.compression.model"));
    assertEquals("nous", e.get("auxiliary.curator.provider"));
    assertEquals("Hermes-4-405B", e.get("auxiliary.curator.model"));
  }

  @Test
  void visionIsLeftOnAutoSoItsFallbackChainStillApplies() {
    Map<String, String> e = auxEntries("openai-codex", "gpt-5.5", null);
    assertFalse(e.containsKey("auxiliary.vision.provider"), "vision must keep its own fallback chain");
    assertFalse(e.containsKey("auxiliary.vision.model"));
  }

  @Test
  void openrouterAuxiliaryModelIdKeptVerbatim() {
    Map<String, String> e = auxEntries("openrouter", "anthropic/claude-sonnet-4", null);
    assertEquals("anthropic/claude-sonnet-4", e.get("auxiliary.compression.model"));
    assertEquals("openrouter", e.get("auxiliary.compression.provider"));
  }

  @Test
  void customEndpointPinsCustomProviderAndBaseUrl() {
    Map<String, String> e = auxEntries("ollama", "qwen3:8b", "http://host.docker.internal:11434/v1");
    assertEquals("custom", e.get("auxiliary.compression.provider"));
    assertEquals("qwen3:8b", e.get("auxiliary.compression.model"));
    assertEquals("http://host.docker.internal:11434/v1", e.get("auxiliary.compression.base_url"));
  }

  @Test
  void nothingConcreteToPinLeavesEveryTaskOnAuto() {
    // pinning at a provider that does not resolve is worse than the auto chain
    assertTrue(auxEntries("nous", "", null).isEmpty(), "blank model");
    assertTrue(auxEntries("nous", null, null).isEmpty(), "null model");
    assertTrue(auxEntries("auto", "some-model", null).isEmpty(), "auto provider, no endpoint");
    assertTrue(auxEntries(null, "some-model", null).isEmpty(), "null provider, no endpoint");
  }

  @Test
  void auxiliaryPinUsesTheSameProviderNormalizationAsTheModelWrite() {
    // "nousresearch" -> "nous" on both halves, or the aux tasks point at a
    // provider id hermes' resolver does not know
    Map<String, String> model = entries("nousresearch", "Hermes-4-405B", null);
    Map<String, String> aux = auxEntries("nousresearch", "Hermes-4-405B", null);
    assertEquals(model.get("model.provider"), aux.get("auxiliary.compression.provider"));
  }

  // ── override resolver: auxiliaryTarget ─────────────────────────────────────

  @Test
  void noOverrideRunsSideTasksOnTheMainModel() {
    for (AuxiliaryModelSpec spec : new AuxiliaryModelSpec[] {
        null,
        new AuxiliaryModelSpec(null, null, null, null),
        new AuxiliaryModelSpec("openrouter", "", null, null)}) {   // provider alone pins nothing
      HermesProfiles.ModelTarget t =
          HermesProfiles.auxiliaryTarget("nous", "Hermes-4-405B", null, spec);
      assertEquals("nous", t.provider());
      assertEquals("Hermes-4-405B", t.model());
    }
  }

  @Test
  void overrideSendsSideTasksToItsOwnProviderAndModel() {
    HermesProfiles.ModelTarget t = HermesProfiles.auxiliaryTarget(
        "anthropic", "claude-opus-4-8", null,
        new AuxiliaryModelSpec("openrouter", "anthropic/claude-haiku-4-5", null, "sk-or-x"));
    assertEquals("openrouter", t.provider());
    assertEquals("anthropic/claude-haiku-4-5", t.model());
    assertEquals(null, t.baseUrl(), "an override with its own provider carries its own endpoint");
  }

  @Test
  void modelOnlyOverrideKeepsTheMainProviderAndEndpoint() {
    // "same local ollama, smaller model" must not need the URL repeated
    HermesProfiles.ModelTarget t = HermesProfiles.auxiliaryTarget(
        "ollama", "qwen3:32b", "http://host.docker.internal:11434/v1",
        new AuxiliaryModelSpec("", "qwen3:8b", null, null));
    assertEquals("ollama", t.provider());
    assertEquals("qwen3:8b", t.model());
    assertEquals("http://host.docker.internal:11434/v1", t.baseUrl());
  }

  @Test
  void overrideFlowsIntoTheAuxiliaryWritePlan() {
    HermesProfiles.ModelTarget t = HermesProfiles.auxiliaryTarget(
        "anthropic", "claude-opus-4-8", null,
        new AuxiliaryModelSpec("openrouter", "anthropic/claude-haiku-4-5", null, null));
    Map<String, String> e = auxEntries(t.provider(), t.model(), t.baseUrl());
    assertEquals("openrouter", e.get("auxiliary.compression.provider"));
    assertEquals("anthropic/claude-haiku-4-5", e.get("auxiliary.compression.model"));
    // and the main model is untouched by the override
    assertEquals("claude-opus-4-8", entries("anthropic", "claude-opus-4-8", null).get("model.default"));
  }

  // ── read-back parser: parseConfig ──────────────────────────────────────────

  @Test
  void structuredMapWithProviderKeepsNamespacedModel() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "openrouter", "default", "anthropic/claude-sonnet-4")));
    assertEquals("openrouter", info.provider());
    // must NOT be truncated to "claude-sonnet-4"
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void structuredNousRoundTrips() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "nous", "default", "Hermes-4-405B", "base_url", "")));
    assertEquals("nous", info.provider());
    assertEquals("Hermes-4-405B", info.model());
  }

  @Test
  void customEndpointMapReadsBlankProviderAsAuto() {
    ConfigInfo info = profiles.parseConfig(Map.of(
        "model", Map.of("provider", "", "default", "qwen3:8b", "base_url", "http://x/v1")));
    assertEquals("auto", info.provider());
    assertEquals("qwen3:8b", info.model());
  }

  @Test
  void legacyScalarModelStringStillSplits() {
    ConfigInfo info = profiles.parseConfig(Map.of("model", "anthropic/claude-opus-4-8"));
    assertEquals("anthropic", info.provider());
    assertEquals("claude-opus-4-8", info.model());
  }

  @Test
  void emptyConfigDefaults() {
    ConfigInfo info = profiles.parseConfig(Map.of());
    assertEquals("auto", info.provider());
    assertEquals("", info.model());
  }

  @Test
  void terminalCwdIsRead() {
    ConfigInfo info = profiles.parseConfig(new LinkedHashMap<>(Map.of(
        "model", "nous/Hermes-4-405B",
        "terminal", Map.of("cwd", "/work"))));
    assertEquals("/work", info.cwd());
  }

  @Test
  void roundTripWritePlanThenParseIsStableForOpenrouter() {
    // simulate: write plan -> resulting model map -> parse back
    Map<String, String> plan = entries("openrouter", "anthropic/claude-sonnet-4", null);
    Map<String, Object> modelMap = new LinkedHashMap<>();
    modelMap.put("provider", plan.get("model.provider"));
    modelMap.put("default", plan.get("model.default"));
    modelMap.put("base_url", plan.get("model.base_url"));
    ConfigInfo info = profiles.parseConfig(Map.of("model", modelMap));
    assertEquals("openrouter", info.provider());
    assertEquals("anthropic/claude-sonnet-4", info.model());
  }

  @Test
  void planWipesThenSetsDefault() {
    List<String[]> plan = HermesProfiles.modelConfigEntries("nous", "Hermes-4-405B", null);
    assertEquals("model", plan.get(0)[0], "wipe first");
    assertEquals("", plan.get(0)[1]);
    assertEquals("model.default", plan.get(1)[0], "then promote back to a map with the default");
  }

  @Test
  void mcpStartsUnknownCachesHandshakeFailureAndInvalidatesOnConfigChange() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfiles liveProfiles = new HermesProfiles(dockerExec, new ObjectMapper(), new HermesConfigEditor());
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

    assertEquals("unknown", liveProfiles.listMcpServers("unix:///sock", "cid", "ops", config).get(0).status());
    assertEquals("error", liveProfiles.testMcpServer("unix:///sock", "cid", "ops", "tp").status());
    assertEquals("error", liveProfiles.listMcpServers("unix:///sock", "cid", "ops", config).get(0).status());

    server.put("url", "http://host.docker.internal:9999/mcp");
    assertEquals("unknown", liveProfiles.listMcpServers("unix:///sock", "cid", "ops", config).get(0).status());
  }

  @Test
  void disabledMcpIsNeverReportedConnected() {
    Map<String, Object> config = Map.of("mcp_servers", Map.of("off", Map.of(
        "url", "http://example.test/mcp", "enabled", false)));
    AgentMcpServerDto result = profiles.listMcpServers("unix:///sock", "cid", "ops", config).get(0);
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
    AgentMcpServerDto dto = profiles.listMcpServers("unix:///sock", "cid", "ops", root).get(0);
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
    HermesProfiles liveProfiles = new HermesProfiles(dockerExec, new ObjectMapper(), new HermesConfigEditor());
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, config, ""));

    assertThrows(ResourceConflictException.class, () -> liveProfiles.updateMcpServer(
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
    assertEquals(12, HermesProfiles.parseToolCount("Connected: 3 tools\nTools discovered: 12"));
    assertEquals(true, HermesProfiles.mcpProbeSucceeded("  ✓ Connected (25ms)\n  ✓ Tools discovered: 12"));
    assertEquals(false, HermesProfiles.mcpProbeSucceeded("  ✗ Connection failed (7000ms)"));
  }

  @Test
  void gatewayLogParserPreservesTimeIdentityAndSeverity() {
    String output = """
        2026-07-11 10:24:39.656561717  gateway started
        not a supervised gateway record
        2026-07-11 10:24:40.000000000  \u001B[33mWARNING provider error is recoverable\u001B[0m
        2026-07-11 10:24:41.123000000  PermissionError: denied
        """;

    var lines = HermesProfiles.parseGatewayLogs("trader-00", output);

    assertEquals(3, lines.size());
    assertEquals("trader-00", lines.get(0).source());
    assertEquals("info", lines.get(0).level());
    assertEquals(1783765479656L, lines.get(0).ts());
    assertEquals("warn", lines.get(1).level(), "an explicit warning wins over 'error' in its message");
    assertEquals("WARNING provider error is recoverable", lines.get(1).msg());
    assertEquals("error", lines.get(2).level());
  }

  @Test
  void stoppedContainerHasNoReadableProfileInventory() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    when(dockerExec.runAsUser(anyString(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class))).thenThrow(new ConflictException("container is not running"));

    assertEquals(List.of(), new HermesProfiles(dockerExec, new ObjectMapper(), new HermesConfigEditor()).list("unix:///sock", "stopped"));
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
