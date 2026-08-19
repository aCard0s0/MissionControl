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
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
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
 *
 * <p>Its lifetime is tested too: entries must expire, and must be evicted when the profile
 * or container they describe goes away, or the map only ever grows.
 */
class HermesProfileMcpTest {

  private static final DockerHostRef HOST = new DockerHostRef("dh-local", "unix:///sock");

  private static final HermesConfigEditor EDITOR = new HermesConfigEditor();

  /** Listing only reads the map it is handed, so no exec seam is required. */
  private final HermesProfileMcp mcp = new HermesProfileMcp(null, EDITOR);

  private static HermesProfileMcp liveMcp(DockerExecService dockerExec) {
    return new HermesProfileMcp(new HermesContainerFiles(dockerExec), new HermesConfigEditor());
  }

  private static HermesProfileMcp liveMcp(DockerExecService dockerExec, LongSupplier clock) {
    return new HermesProfileMcp(
        new HermesContainerFiles(dockerExec), new HermesConfigEditor(), clock);
  }

  /** Cache lifetime is measured in seconds, so the tests move a clock instead of sleeping. */
  private static final class FakeClock implements LongSupplier {

    private long now = 1_700_000_000_000L;

    @Override
    public long getAsLong() {
      return now;
    }

    void advance(long millis) {
      now += millis;
    }
  }

  /** One enabled server named {@code tp}, as both the config read and the listing see it. */
  private static final String CONFIG_YAML = """
      mcp_servers:
        tp:
          url: http://host.docker.internal:8050/mcp/sse
          transport: sse
          enabled: true
      """;

  private static Map<String, Object> configMap() {
    return Map.of("mcp_servers", Map.of("tp", new LinkedHashMap<>(Map.of(
        "url", "http://host.docker.internal:8050/mcp/sse",
        "transport", "sse",
        "enabled", true))));
  }

  /**
   * Answers every exec by what it is: an {@code hermes … mcp test} argv gets a successful
   * handshake, anything else (the config read, {@code hermes profile delete}) gets the
   * config. Sequential stubbing cannot express that once a flow interleaves probes with
   * other commands.
   */
  private static DockerExecService probingExec() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(),
        anyBoolean(), anyBoolean(), any(Duration.class)))
        .thenAnswer(invocation -> {
          List<?> command = invocation.getArgument(3);
          return command.contains("mcp")
              ? new DockerExecService.ExecResult(0, "  ✓ Connected (25ms)", "")
              : new DockerExecService.ExecResult(0, CONFIG_YAML, "");
        });
    return dockerExec;
  }

  private static String status(HermesProfileMcp mcp, String containerId, String profileName) {
    return mcp.list(HOST, containerId, profileName, configMap()).get(0).status();
  }

  /**
   * The cache is bounded by a TTL, so a probe filed against a since-removed profile,
   * container, or host cannot sit in the map for the life of the process.
   *
   * <p>Both edges matter. Past the TTL the badge has to fall back to "unknown", because
   * {@code list} never re-probes and a ten-second-old reachability check is not evidence.
   * Inside it the probe has to still be served, or every listing pays a container exec per
   * configured server — which is the cost the cache exists to avoid.
   */
  @Test
  void probeCacheServesInsideTheTtlAndReportsUnknownPastIt() {
    FakeClock clock = new FakeClock();
    HermesProfileMcp liveMcp = liveMcp(probingExec(), clock);

    assertEquals("connected", liveMcp.test(HOST, "cid", "ops", "tp").status());
    assertEquals("connected", status(liveMcp, "cid", "ops"));

    clock.advance(9_999);
    assertEquals("connected", status(liveMcp, "cid", "ops"),
        "a probe inside the TTL is still served, or a listing costs one exec per server");

    clock.advance(1);
    assertEquals("unknown", status(liveMcp, "cid", "ops"),
        "past the TTL the probe is not evidence, and list() does not re-probe");
  }

  /**
   * The map is bounded, not merely expiring on read.
   *
   * <p>This is the assertion the other cache tests cannot make. A listing reports "unknown"
   * for an expired entry whether it was swept, dropped on read, or never taken — so through
   * {@code list} alone, a cache that evicts is indistinguishable from one that only grows.
   * The keys that matter most are exactly the ones no listing visits again: a container
   * removed with {@code docker rm} fires no hook, and nothing ever asks after its profiles.
   */
  @Test
  void filingAProbeSweepsExpiredEntriesNoListingWouldEverVisitAgain() {
    FakeClock clock = new FakeClock();
    HermesProfileMcp liveMcp = liveMcp(probingExec(), clock);

    liveMcp.test(HOST, "removed-cid", "ops", "tp");
    liveMcp.test(HOST, "removed-cid", "eng", "tp");
    assertEquals(2, liveMcp.cachedProbeCount());

    // The container is gone now — removed outside Mission Control, so no eviction hook ran.
    clock.advance(10_000);
    liveMcp.test(HOST, "live-cid", "ops", "tp");

    assertEquals(1, liveMcp.cachedProbeCount(),
        "a write sweeps what the TTL expired, including keys the read path never reaches");
  }

  /** Deleting a profile drops its probes and nothing else: sibling profiles on the same
   *  container are untouched, since only the deleted one's key component changed. */
  @Test
  void deletingAProfileDropsItsProbesAndLeavesSiblingProfilesCached() {
    DockerExecService dockerExec = probingExec();
    FakeClock clock = new FakeClock();
    HermesProfileMcp liveMcp = liveMcp(dockerExec, clock);
    HermesProfiles profiles = AgentsWiring.profiles(dockerExec, liveMcp);

    liveMcp.test(HOST, "cid", "ops", "tp");
    liveMcp.test(HOST, "cid", "eng", "tp");
    assertEquals("connected", status(liveMcp, "cid", "ops"));
    assertEquals("connected", status(liveMcp, "cid", "eng"));

    profiles.delete(HOST, "cid", "ops");

    assertEquals("unknown", status(liveMcp, "cid", "ops"));
    assertEquals("connected", status(liveMcp, "cid", "eng"),
        "a sibling profile's probes are not collateral of the delete");
  }

  /**
   * An image update recreates the container under a new id, so the old id's probes are
   * dropped — they are evidence about a container that no longer exists.
   *
   * <p>The match is on the old container id alone. Docker ids are globally unique, so the
   * {@code hostId} the listener is handed is not needed to identify the entries, and this
   * cache never has to translate it to a host url.
   */
  @Test
  void replacingAContainerDropsTheOldIdsProbes() {
    FakeClock clock = new FakeClock();
    HermesProfileMcp liveMcp = liveMcp(probingExec(), clock);

    liveMcp.test(HOST, "old-cid", "ops", "tp");
    liveMcp.test(HOST, "other-cid", "ops", "tp");
    assertEquals("connected", status(liveMcp, "old-cid", "ops"));

    assertEquals(0, liveMcp.onContainerReplaced("dh-local", "old-cid", "new-cid"),
        "a cache moves no dashboard rows for the update log to count");

    assertEquals("unknown", status(liveMcp, "old-cid", "ops"));
    assertEquals("unknown", status(liveMcp, "new-cid", "ops"),
        "the probe follows nothing: the replacement's reachability is unproven");
    assertEquals("connected", status(liveMcp, "other-cid", "ops"),
        "another container's probes are not collateral of the replacement");
  }

  @Test
  void mcpStartsUnknownCachesHandshakeFailureAndInvalidatesOnConfigChange() {
    DockerExecService dockerExec = mock(DockerExecService.class);
    // A stopped clock, because this is about invalidation by fingerprint, not by age: on a
    // real one the assertions below hold only while the whole test runs inside the TTL.
    HermesProfileMcp liveMcp = liveMcp(dockerExec, new FakeClock());
    Map<String, Object> server = new LinkedHashMap<>(Map.of(
        "url", "http://host.docker.internal:8050/mcp/sse",
        "transport", "sse",
        "enabled", true));
    Map<String, Object> config = Map.of("mcp_servers", Map.of("tp", server));
    String yaml = "mcp_servers:\n  tp:\n    url: http://host.docker.internal:8050/mcp/sse\n    transport: sse\n    enabled: true\n";
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, yaml, ""))
        // Hermes currently exits 0 even when its protocol handshake fails.
        .thenReturn(new DockerExecService.ExecResult(0, "✗ Connection failed", ""));

    assertEquals("unknown", liveMcp.list(HOST, "cid", "ops", config).get(0).status());
    assertEquals("error", liveMcp.test(HOST, "cid", "ops", "tp").status());
    assertEquals("error", liveMcp.list(HOST, "cid", "ops", config).get(0).status());

    server.put("url", "http://host.docker.internal:9999/mcp");
    assertEquals("unknown", liveMcp.list(HOST, "cid", "ops", config).get(0).status());
  }

  @Test
  void disabledMcpIsNeverReportedConnected() {
    Map<String, Object> config = Map.of("mcp_servers", Map.of("off", Map.of(
        "url", "http://example.test/mcp", "enabled", false)));
    AgentMcpServerDto result = mcp.list(HOST, "cid", "ops", config).get(0);
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
    McpServerDefinition definition = McpServerDefinition.from(new AddMcpServerRequest(
        "events", "sse", "https://mcp.example.test/sse", null, null, true, null, null));
    String config = EDITOR.addMcpServer(
        "model: nous/Hermes-4-405B\n", "/opt/data/config.yaml", definition);

    Map<String, Object> root = yamlMap(config);
    assertEquals("sse", mcpServer(config, "events").get("transport"));
    AgentMcpServerDto dto = mcp.list(HOST, "cid", "ops", root).get(0);
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
        McpServerDefinition.from(new AddMcpServerRequest("tools", "stdio", null, "uvx", "  ", null, null, null)));
    Map<String, Object> cleared = mcpServer(argsCleared, "tools");
    assertFalse(cleared.containsKey("args"));
    assertEquals(false, cleared.get("enabled"), "an omitted enabled value preserves current state");
    assertEquals("retained", cleared.get("vendor_option"));

    String switchedToSse = EDITOR.updateMcpServer(
        argsCleared, "/opt/data/config.yaml", "tools",
        McpServerDefinition.from(new AddMcpServerRequest(
            "tools", "sse", "https://mcp.example.test/sse", null, null, null, null, null)));
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
        McpServerDefinition.from(new AddMcpServerRequest(
            "tools", "sse", "https://mcp.example.test/sse", null, null, null, Map.of(), null)));
    Map<String, Object> clearedNetwork = mcpServer(headersCleared, "tools");
    assertFalse(clearedNetwork.containsKey("headers"), "an explicit empty header map clears headers");
    assertEquals("retained", clearedNetwork.get("vendor_option"));

    // Simulate an HTTP-only header configured outside Mission Control. Moving
    // to stdio must clear both the network endpoint and its credentials.
    String switchedToStdio = EDITOR.updateMcpServer(
        networkWithHeader, "/opt/data/config.yaml", "tools",
        McpServerDefinition.from(new AddMcpServerRequest("tools", "stdio", null, "node", "server.js", null, null, null)));
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
        McpServerDefinition.from(new AddMcpServerRequest(
            "new-name", "http", "https://new.example.test/mcp", null, null, null, null, null)));
    Map<String, Object> renamedServers = mcpServers(renamed);
    assertFalse(renamedServers.containsKey("old-name"));
    assertTrue(renamedServers.containsKey("new-name"));
    assertTrue(renamedServers.containsKey("occupied"));
    assertEquals("retained", mcpServer(renamed, "new-name").get("vendor_option"));

    DockerExecService dockerExec = mock(DockerExecService.class);
    HermesProfileMcp liveMcp = liveMcp(dockerExec);
    when(dockerExec.runAsUser(any(), anyString(), anyString(), any(), anyString(), anyBoolean(),
        anyBoolean(), any(Duration.class)))
        .thenReturn(new DockerExecService.ExecResult(0, config, ""));

    assertThrows(ResourceConflictException.class, () -> liveMcp.update(
        HOST, "cid", "ops", "old-name",
        McpServerDefinition.from(new AddMcpServerRequest(
            "occupied", "http", "https://new.example.test/mcp", null, null, null, null, null))));
    // No temp-file write (and no deletion) is attempted after the collision is
    // discovered. Asserted by operation rather than by a total exec count, because the
    // profile-existence guard also reads before the config read.
    verify(dockerExec, never()).runAsUser(
        any(), anyString(), anyString(), any(), eq("write MCP configuration"), anyBoolean(),
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
