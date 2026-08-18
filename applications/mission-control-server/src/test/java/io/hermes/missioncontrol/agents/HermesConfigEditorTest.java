package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The config-edit rules on their own. {@link HermesConfigEditor} touches no container, so
 * every decision it makes — transport switching, rename collisions, header and environment
 * validation, refusing to rewrite a file it cannot parse — is reachable here without a
 * Docker mock. Previously these rules were only ever exercised incidentally through
 * {@link HermesProfilesTest}.
 */
class HermesConfigEditorTest {

  private static final String PATH = "/home/hermes/.hermes/profiles/scout/config.yaml";

  private final HermesConfigEditor editor = new HermesConfigEditor();

  private Map<?, ?> serverIn(String configYaml, String name) {
    Object root = new Yaml().load(configYaml);
    Map<?, ?> servers = (Map<?, ?>) ((Map<?, ?>) root).get("mcp_servers");
    return (Map<?, ?>) servers.get(name);
  }

  private static AddMcpServerRequest http(String name, String url) {
    return new AddMcpServerRequest(name, "http", url, null, null, null);
  }

  private static AddMcpServerRequest stdio(String name, String command, String args) {
    return new AddMcpServerRequest(name, "stdio", null, command, args, null);
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
        "", PATH, new AddMcpServerRequest("stream", "SSE", "https://x.internal/sse", null, null, null));

    assertEquals("sse", serverIn(result, "stream").get("transport"));
  }

  @Test
  void switchingFromStdioToHttpClearsCommandArgsAndEnv() {
    String stdioConfig = editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "stdio", null, "npx", "-y @scope/files", null, null,
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
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of("Authorization", "Bearer t")));

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
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of("Authorization", "Bearer t")));

    // null means "the caller did not edit this advanced field"
    String untouched = editor.updateMcpServer(
        withHeader, PATH, "files", http("files", "https://files.internal/mcp"));
    assertEquals(Map.of("Authorization", "Bearer t"), serverIn(untouched, "files").get("headers"));

    // an explicit empty map is a deliberate clear
    String cleared = editor.updateMcpServer(
        withHeader, PATH, "files",
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, null,
            Map.of()));
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
    IllegalStateException unparseable = assertThrows(IllegalStateException.class, () ->
        editor.parseForEdit("\tkey: [unterminated", PATH));
    assertTrue(unparseable.getMessage().contains(PATH), "the message must name the file it refused");

    // a valid YAML document that is not a mapping is equally unusable
    assertThrows(IllegalStateException.class, () -> editor.parseForEdit("- a\n- b", PATH));

    // mcp_servers present but not a map
    assertThrows(IllegalStateException.class, () ->
        editor.addMcpServer("mcp_servers: oops\n", PATH, http("files", "https://x.internal/mcp")));

    // one entry present but not a map — refuse rather than overwrite it
    String malformedEntry = "mcp_servers:\n  files: oops\n";
    assertThrows(IllegalStateException.class, () ->
        editor.addMcpServer(malformedEntry, PATH, http("files", "https://x.internal/mcp")));
    assertThrows(IllegalStateException.class, () ->
        editor.updateMcpServer(malformedEntry, PATH, "files", http("files", "https://x.internal/mcp")));
    assertThrows(IllegalStateException.class, () ->
        editor.setMcpServerEnabled(malformedEntry, PATH, "files", false));
  }

  @Test
  void enabledDefaultsToTrueOnlyWhenTheEntryDoesNotAlreadyCarryIt() {
    String disabled = editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "http", "https://files.internal/mcp", null, null, false));
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
  void headerNamesAndValuesWithLineBreaksAreRejected() {
    // header injection: a CRLF in either half would forge extra request headers
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "http", "https://x.internal/mcp", null, null, null,
            Map.of("X-Evil\r\nInjected", "v"))));
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "http", "https://x.internal/mcp", null, null, null,
            Map.of("Authorization", "Bearer t\r\nX-Evil: 1"))));

    Map<String, String> blankName = new LinkedHashMap<>();
    blankName.put("  ", "v");
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "http", "https://x.internal/mcp", null, null, null,
            blankName)));
  }

  @Test
  void invalidStdioEnvironmentKeysAndValuesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "stdio", null, "npx", null, null, null,
            Map.of("1LEADING_DIGIT", "v"))));
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "stdio", null, "npx", null, null, null,
            Map.of("HAS-DASH", "v"))));
    // a value carrying a line break would inject a second entry into the env block
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "stdio", null, "npx", null, null, null,
            Map.of("TOKEN", "a\nEVIL=1"))));
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH,
        new AddMcpServerRequest("files", "stdio", null, "npx", null, null, null,
            Map.of("TOKEN", "a\0b"))));
  }

  @Test
  void quotedArgsKeepTheirInternalSpaces() {
    assertEquals(
        List.of("--flag", "two words", "and more", "tail"),
        editor.splitArgs("--flag 'two words' \"and more\"   tail"));
    // an unterminated quote still yields the trailing token rather than dropping it
    assertEquals(List.of("--flag", "two words"), editor.splitArgs("--flag 'two words"));
    assertEquals(List.of(), editor.splitArgs("   "));
  }

  @Test
  void aTransportThatIsNotStdioHttpOrSseIsRejectedAndSoIsAMissingEndpoint() {
    assertThrows(IllegalArgumentException.class, () -> editor.addMcpServer(
        "", PATH, new AddMcpServerRequest("files", "grpc", "https://x.internal", null, null, null)));
    // stdio without a command, and http without a url, are both unusable definitions
    assertThrows(IllegalArgumentException.class, () ->
        editor.addMcpServer("", PATH, stdio("files", "  ", null)));
    assertThrows(IllegalArgumentException.class, () ->
        editor.addMcpServer("", PATH, http("files", "  ")));
  }

  @Test
  void aBlankOrNullServerNameIsRejected() {
    assertThrows(IllegalArgumentException.class, () -> editor.serverName(null));
    assertThrows(IllegalArgumentException.class, () -> editor.serverName("   "));
    assertEquals("files", editor.serverName("  files  "));
  }
}
