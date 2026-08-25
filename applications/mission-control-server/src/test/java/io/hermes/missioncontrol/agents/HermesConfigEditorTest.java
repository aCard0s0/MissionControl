package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.OutboundWebhookDto;
import io.hermes.missioncontrol.agents.api.OutboundWebhookRequest;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * What an edit does to {@code config.yaml}. {@link HermesConfigEditor} touches no container, so
 * every decision it makes — which keys a transport owns, rename collisions, refusing to rewrite
 * a file it cannot parse — is reachable here without a Docker mock.
 *
 * <p>What a valid definition <em>is</em> moved to {@link McpServerDefinitionTest} along with the
 * rules themselves. The editor now takes an already-validated {@link McpServerDefinition} and is
 * only responsible for the file's shape.
 */
class HermesConfigEditorTest {

  private static final String PATH = "/home/hermes/.hermes/profiles/scout/config.yaml";

  private final HermesConfigEditor editor = new HermesConfigEditor();

  private Map<?, ?> serverIn(String configYaml, String name) {
    Object root = new Yaml().load(configYaml);
    Map<?, ?> servers = (Map<?, ?>) ((Map<?, ?>) root).get("mcp_servers");
    return (Map<?, ?>) servers.get(name);
  }

  private static McpServerDefinition definition(
      String name, String transport, String url, String command, String args, Boolean enabled,
      Map<String, String> headers, Map<String, String> environment) {
    return McpServerDefinition.from(new AddMcpServerRequest(
        name, transport, url, command, args, enabled, headers, environment));
  }

  private static McpServerDefinition http(String name, String url) {
    return definition(name, "http", url, null, null, null, null, null);
  }

  private static McpServerDefinition stdio(String name, String command, String args) {
    return definition(name, "stdio", null, command, args, null, null, null);
  }

  @Test
  void addingAServerToAnEmptyConfigCreatesTheMcpServersMap() {
    String result = editor.addMcpServer("", PATH, http("files", "https://files.internal/mcp"));

    Map<?, ?> server = serverIn(result, "files");
    assertEquals("https://files.internal/mcp", server.get("url"));
    // absent from the request, so the editor supplies the default rather than leaving it unset
    assertEquals(Boolean.TRUE, server.get("enabled"));
  }

  @Test
  void anSseServerStoresTheExplicitTransportRatherThanInferringItFromTheUrl() {
    // SSE cannot be told apart from HTTP by looking at the URL, and used to come back
    // from the API as "http" — the transport the caller asked for is the one persisted
    String result = editor.addMcpServer(
        "", PATH, McpServerDefinition.from(new AddMcpServerRequest("stream", "SSE", "https://x.internal/sse", null, null, null, null, null)));

    assertEquals("sse", serverIn(result, "stream").get("transport"));
  }

  @Test
  void switchingFromStdioToHttpClearsCommandArgsAndEnv() {
    String stdioConfig = editor.addMcpServer(
        "", PATH,
        definition("files", "stdio", null, "npx", "-y @scope/files", null, null,
            Map.of("TOKEN", "secret")));

    String result = editor.updateMcpServer(
        stdioConfig, PATH, "files", http("files", "https://files.internal/mcp"));

    Map<?, ?> server = serverIn(result, "files");
    assertEquals("https://files.internal/mcp", server.get("url"));
    // stale credentials and a stale command must not survive a transport change
    assertFalse(server.containsKey("command"), "command survived the switch to http");
    assertFalse(server.containsKey("args"), "args survived the switch to http");
    assertFalse(server.containsKey("env"), "env survived the switch to http");
  }

  @Test
  void switchingFromHttpToStdioClearsUrlAndHeaders() {
    String httpConfig = editor.addMcpServer(
        "", PATH,
        definition("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of("Authorization", "Bearer t"), null));

    String result = editor.updateMcpServer(httpConfig, PATH, "files", stdio("files", "npx", null));

    Map<?, ?> server = serverIn(result, "files");
    assertEquals("npx", server.get("command"));
    assertFalse(server.containsKey("url"), "url survived the switch to stdio");
    assertFalse(server.containsKey("headers"), "headers survived the switch to stdio");
  }

  @Test
  void anOmittedHeaderMapPreservesHeadersWhileAnEmptyMapClearsThem() {
    String withHeader = editor.addMcpServer(
        "", PATH,
        definition("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of("Authorization", "Bearer t"), null));

    // null means "the caller did not edit this advanced field"
    String untouched = editor.updateMcpServer(
        withHeader, PATH, "files", http("files", "https://files.internal/mcp"));
    assertEquals(Map.of("Authorization", "Bearer t"), serverIn(untouched, "files").get("headers"));

    // an explicit empty map is a deliberate clear
    String cleared = editor.updateMcpServer(
        withHeader, PATH, "files",
        definition("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of(), null));
    assertFalse(serverIn(cleared, "files").containsKey("headers"));
  }

  @Test
  void keysUnknownToMissionControlSurviveAnEdit() {
    String seeded = """
        model: claude-opus-5
        mcp_servers:
          files:
            transport: http
            url: https://files.internal/mcp
            tool_filter:
              - read_file
            some_future_hermes_option: 42
        """;

    String result = editor.setMcpServerEnabled(seeded, PATH, "files", false);

    Map<?, ?> server = serverIn(result, "files");
    assertEquals(Boolean.FALSE, server.get("enabled"));
    assertEquals(List.of("read_file"), server.get("tool_filter"));
    assertEquals(42, server.get("some_future_hermes_option"));
    // a sibling top-level key is not collateral damage
    assertEquals("claude-opus-5", ((Map<?, ?>) new Yaml().load(result)).get("model"));
  }

  @Test
  void renamingAServerMovesItsDefinitionAndDropsTheOldKey() {
    String seeded = editor.addMcpServer("", PATH, http("files", "https://files.internal/mcp"));

    String result = editor.updateMcpServer(
        seeded, PATH, "files", http("documents", "https://files.internal/mcp"));

    Map<?, ?> servers = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(result)).get("mcp_servers");
    assertFalse(servers.containsKey("files"), "the old key outlived the rename");
    assertEquals("https://files.internal/mcp", ((Map<?, ?>) servers.get("documents")).get("url"));
  }

  @Test
  void renamingAServerOntoAnExistingNameIsAConflict() {
    String seeded = editor.addMcpServer(
        editor.addMcpServer("", PATH, http("files", "https://a.internal/mcp")),
        PATH, http("documents", "https://b.internal/mcp"));

    assertThrows(ResourceConflictException.class, () ->
        editor.updateMcpServer(seeded, PATH, "files", http("documents", "https://a.internal/mcp")));
  }

  @Test
  void updatingOrTogglingAnUnknownServerIsANotFound() {
    String seeded = editor.addMcpServer("", PATH, http("files", "https://files.internal/mcp"));

    assertThrows(NoSuchElementException.class, () ->
        editor.updateMcpServer(seeded, PATH, "ghost", http("ghost", "https://x.internal/mcp")));
    assertThrows(NoSuchElementException.class, () ->
        editor.setMcpServerEnabled(seeded, PATH, "ghost", true));
  }

  @Test
  void anUnparseableOrMalformedConfigIsRefusedRatherThanRewritten() {
    // the guard that stops an edit from wiping a profile's whole config
    ResourceConflictException unparseable = assertThrows(ResourceConflictException.class, () ->
        editor.parseForEdit("\tkey: [unterminated", PATH));
    assertTrue(unparseable.getMessage().contains(PATH), "the message must name the file it refused");

    // a valid YAML document that is not a mapping is equally unusable
    assertThrows(ResourceConflictException.class, () -> editor.parseForEdit("- a\n- b", PATH));

    // mcp_servers present but not a map
    assertThrows(ResourceConflictException.class, () ->
        editor.addMcpServer("mcp_servers: oops\n", PATH, http("files", "https://x.internal/mcp")));

    // one entry present but not a map — refuse rather than overwrite it
    String malformedEntry = "mcp_servers:\n  files: oops\n";
    assertThrows(ResourceConflictException.class, () ->
        editor.addMcpServer(malformedEntry, PATH, http("files", "https://x.internal/mcp")));
    assertThrows(ResourceConflictException.class, () ->
        editor.updateMcpServer(malformedEntry, PATH, "files", http("files", "https://x.internal/mcp")));
    assertThrows(ResourceConflictException.class, () ->
        editor.setMcpServerEnabled(malformedEntry, PATH, "files", false));
  }

  @Test
  void enabledDefaultsToTrueOnlyWhenTheEntryDoesNotAlreadyCarryIt() {
    String disabled = editor.addMcpServer(
        "", PATH,
        definition("files", "http", "https://files.internal/mcp", null, null, false, null, null));
    assertEquals(Boolean.FALSE, serverIn(disabled, "files").get("enabled"));

    // a later edit that says nothing about `enabled` must not silently re-enable it
    String edited = editor.updateMcpServer(
        disabled, PATH, "files", http("files", "https://files.internal/mcp"));
    assertEquals(Boolean.FALSE, serverIn(edited, "files").get("enabled"));
  }

  @Test
  void removingAServerThatWasNeverThereIsNotAnError() {
    String seeded = editor.addMcpServer("", PATH, http("files", "https://files.internal/mcp"));

    String result = editor.removeMcpServer(seeded, PATH, "ghost");

    Map<?, ?> servers = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(result)).get("mcp_servers");
    assertEquals(1, servers.size());
    assertTrue(servers.containsKey("files"));
  }

  @Test
  void anUnparseableConfigIsNeverRewritten() {
    // rewriting a file we cannot parse would drop whatever else the operator had in it
    assertEquals("refusing to rewrite unparseable " + PATH,
        assertThrows(ResourceConflictException.class,
            () -> editor.parseForEdit("mcp_servers: [unclosed\n", PATH)).getMessage());
    // a document that parses but is not a mapping is equally unsafe to edit
    assertEquals("refusing to rewrite unparseable " + PATH,
        assertThrows(ResourceConflictException.class,
            () -> editor.parseForEdit("- a list\n", PATH)).getMessage());
  }

  @Test
  void anAbsentConfigStartsFromAnEmptyTree() {
    assertTrue(editor.parseForEdit(null, PATH).isEmpty());
    assertTrue(editor.parseForEdit("   ", PATH).isEmpty());
  }

  // ── hooks.outbound ─────────────────────────────────────────────────────────

  private static OutboundWebhookRequest target(
      String name, String url, List<String> events, String matcher, Integer timeout,
      String secretEnv) {
    return new OutboundWebhookRequest(name, url, events, matcher, timeout, secretEnv);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<?, ?>> outboundIn(String configYaml) {
    Map<?, ?> root = (Map<?, ?>) new Yaml().load(configYaml);
    Map<?, ?> hooks = (Map<?, ?>) root.get("hooks");
    return hooks == null ? List.of() : (List<Map<?, ?>>) hooks.get("outbound");
  }

  @Test
  void addingTheFirstTargetCreatesTheHooksBlockAroundIt() {
    String updated = editor.addOutboundWebhook("model:\n  provider: nous\n", PATH,
        target("ci", "https://ci.example.test/hooks", List.of("on_session_end"), null, 10, "CI_SECRET"));

    Map<?, ?> written = outboundIn(updated).getFirst();
    assertEquals("https://ci.example.test/hooks", written.get("url"));
    assertEquals(List.of("on_session_end"), written.get("events"));
    assertEquals("ci", written.get("name"));
    assertEquals(10, written.get("timeout"));
    assertEquals("CI_SECRET", written.get("secret_env"));
    // and the rest of the config is still there
    assertEquals("nous", ((Map<?, ?>) ((Map<?, ?>) new Yaml().load(updated)).get("model")).get("provider"));
  }

  @Test
  void anOptionalFieldLeftBlankIsNotWrittenAtAll() {
    // hermes treats a key's absence and an empty string differently — an empty matcher would
    // be compiled as a regex that matches everything
    String updated = editor.addOutboundWebhook("", PATH,
        target("", "https://x.test/h", List.of("subagent_stop"), "  ", null, ""));

    Map<?, ?> written = outboundIn(updated).getFirst();
    assertFalse(written.containsKey("name"));
    assertFalse(written.containsKey("matcher"));
    assertFalse(written.containsKey("timeout"));
    assertFalse(written.containsKey("secret_env"));
  }

  @Test
  void anInlineSecretSomeoneSetByHandSurvivesAnEditThroughTheDashboard() {
    // the UI never shows a literal secret, so it must never be the thing that drops it
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
              secret: hand-written-signing-key
        """;

    String updated = editor.updateOutboundWebhook(config, PATH, 0,
        target("renamed", "https://y.test/h", List.of("subagent_stop"), null, null, null));

    Map<?, ?> written = outboundIn(updated).getFirst();
    assertEquals("hand-written-signing-key", written.get("secret"));
    assertEquals("https://y.test/h", written.get("url"));
    assertEquals("renamed", written.get("name"));
  }

  @Test
  void removingTheLastTargetTakesTheEmptyHooksBlockWithIt() {
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
        model:
          provider: nous
        """;

    String updated = editor.removeOutboundWebhook(config, PATH, 0);

    assertFalse(((Map<?, ?>) new Yaml().load(updated)).containsKey("hooks"));
    assertEquals("nous", ((Map<?, ?>) ((Map<?, ?>) new Yaml().load(updated)).get("model")).get("provider"));
  }

  @Test
  void otherKeysUnderHooksKeepTheBlockAliveWhenTheLastTargetGoes() {
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
          shell:
            - name: keep-me
        """;

    String updated = editor.removeOutboundWebhook(config, PATH, 0);

    Map<?, ?> hooks = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(updated)).get("hooks");
    assertFalse(hooks.containsKey("outbound"));
    assertTrue(hooks.containsKey("shell"));
  }

  @Test
  void anIndexThatIsNoLongerThereIsRefusedRatherThanRewritingWhateverMovedIntoIt() {
    // position is the only handle hermes gives a target, so a stale one from a page whose
    // config changed underneath must not silently edit its neighbour
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
        """;

    assertThrows(NoSuchElementException.class,
        () -> editor.updateOutboundWebhook(config, PATH, 1,
            target("", "https://z.test/h", List.of("subagent_stop"), null, null, null)));
    assertThrows(NoSuchElementException.class,
        () -> editor.removeOutboundWebhook(config, PATH, 4));
  }

  @Test
  void aMalformedEntryIsListedAroundRatherThanCrashingTheListing() {
    // hermes skips a bad entry with a warning and keeps delivering to the rest; a listing
    // that threw would report a working profile as having no targets at all
    String config = """
        hooks:
          outbound:
            - "not a mapping"
            - events: [on_session_end]
            - url: https://good.test/h
              events: [on_session_end, subagent_stop]
              name: good
        """;

    List<OutboundWebhookDto> listed = editor.outboundWebhooks(config);

    assertEquals(1, listed.size());
    assertEquals("good", listed.getFirst().name());
    assertEquals(List.of("on_session_end", "subagent_stop"), listed.getFirst().events());
  }

  @Test
  void aLiteralSecretIsFlaggedWithoutItsValueEverLeavingTheFile() {
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
              secret: hand-written-signing-key
        """;

    OutboundWebhookDto listed = editor.outboundWebhooks(config).getFirst();

    assertTrue(listed.literalSecret());
    assertNull(listed.secretEnv());
  }

  @Test
  void aProfileWithNoHooksBlockHasNoTargets() {
    assertEquals(List.of(), editor.outboundWebhooks("model:\n  provider: nous\n"));
    assertEquals(List.of(), editor.outboundWebhooks(""));
    assertEquals(List.of(), editor.outboundWebhooks("hooks:\n  outbound: not-a-list\n"));
  }

  @Test
  void everyOptionalFieldSetIsWrittenAndAKeptTargetReadsBackWithAllOfThem() {
    String updated = editor.addOutboundWebhook("", PATH, target(
        "tools", "http://inside.test/h", List.of("pre_tool_call", "post_tool_call"),
        "terminal|delegate_task", 45, "TOOLS_SECRET"));

    OutboundWebhookDto listed = editor.outboundWebhooks(updated).getFirst();

    assertEquals("tools", listed.name());
    assertEquals("http://inside.test/h", listed.url());
    assertEquals(List.of("pre_tool_call", "post_tool_call"), listed.events());
    assertEquals("terminal|delegate_task", listed.matcher());
    assertEquals(45, listed.timeout());
    assertEquals("TOOLS_SECRET", listed.secretEnv());
    assertFalse(listed.literalSecret());
  }

  @Test
  void aTargetMissingTheFieldsThatMakeItUsableReadsBackAsNullsRatherThanBlanks() {
    // hermes treats an absent key and an empty one differently, so the listing has to as well
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: [on_session_end]
              timeout: not-a-number
        """;

    OutboundWebhookDto listed = editor.outboundWebhooks(config).getFirst();

    assertEquals("", listed.name());
    assertNull(listed.matcher());
    assertNull(listed.timeout());
    assertNull(listed.secretEnv());
  }

  @Test
  void anEntryThatIsNotAMappingIsRefusedForEditRatherThanReplaced() {
    // it is left out of the listing, so an index that lands on it came from somewhere stale
    String config = """
        hooks:
          outbound:
            - "not a mapping"
        """;

    assertThrows(ResourceConflictException.class,
        () -> editor.updateOutboundWebhook(config, PATH, 0,
            target("", "https://z.test/h", List.of("subagent_stop"), null, null, null)));
  }

  @Test
  void aMalformedEventsKeyLeavesTheTargetWithNoEventsRatherThanFailingTheListing() {
    String config = """
        hooks:
          outbound:
            - url: https://x.test/h
              events: on_session_end
            - url: https://y.test/h
              events: [on_session_end, "", null]
        """;

    List<OutboundWebhookDto> listed = editor.outboundWebhooks(config);

    assertEquals(List.of(), listed.getFirst().events());
    assertEquals(List.of("on_session_end"), listed.get(1).events());
  }

  @Test
  void aHooksBlockThatIsNotAMappingIsIgnoredRatherThanCrashing() {
    assertEquals(List.of(), editor.outboundWebhooks("hooks: nope\n"));
  }
}
