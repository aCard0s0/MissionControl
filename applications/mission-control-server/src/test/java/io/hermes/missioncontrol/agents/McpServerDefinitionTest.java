package io.hermes.missioncontrol.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What makes an MCP definition valid, independent of the file it is written to.
 *
 * <p>These rules used to run inside {@link HermesConfigEditor}, against the request record,
 * which meant they only held for callers that arrived through a controller — the catalog
 * materializer and the template applier built the same record by hand and skipped nothing but
 * bean validation. They now live in the canonical constructor, so every construction path
 * runs them; {@link HermesConfigEditorTest} is left asserting the YAML shape.
 */
class McpServerDefinitionTest {

  private static AddMcpServerRequest request(
      String name, String transport, String url, String command, String args,
      Map<String, String> headers, Map<String, String> environment) {
    return new AddMcpServerRequest(
        name, transport, url, command, args, true, headers, environment);
  }

  private static McpServerDefinition http(Map<String, String> headers) {
    return McpServerDefinition.from(
        request("tools", "http", "https://tools.test/mcp", null, null, headers, null));
  }

  private static McpServerDefinition stdio(Map<String, String> environment) {
    return McpServerDefinition.from(
        request("files", "stdio", null, "npx", null, null, environment));
  }

  private static String rejected(Runnable call) {
    return assertThrows(IllegalArgumentException.class, call::run).getMessage();
  }

  /** A single-entry map with a null value, which Map.of cannot express. */
  private static Map<String, String> nullValued(String key) {
    Map<String, String> map = new LinkedHashMap<>();
    map.put(key, null);
    return map;
  }

  // ── the shape a transport implies ──────────────────────────────────────

  @Test
  void aTransportThatIsNotOneOfTheThreeIsRefusedRatherThanGuessedFromTheUrl() {
    // SSE cannot be inferred from a URL, so nothing is inferred at all
    for (String transport : List.of("  ", "grpc", "HTTP2")) {
      assertEquals("invalid transport", rejected(() -> McpServerDefinition.from(
          request("tools", transport, "https://tools.test/mcp", null, null, null, null))));
    }
    assertEquals("invalid transport", rejected(() -> McpServerDefinition.from(
        request("tools", null, "https://tools.test/mcp", null, null, null, null))));
  }

  @Test
  void aTransportIsMatchedCaseInsensitivelyAndKeepsItsLowercaseWireName() {
    McpServerDefinition definition = McpServerDefinition.from(
        request("tools", " SSE ", "https://tools.test/sse", null, null, null, null));

    assertEquals(McpServerDefinition.Transport.SSE, definition.transport());
    assertEquals("sse", definition.transport().wireName());
  }

  @Test
  void aStdioServerWithoutACommandIsRefused() {
    assertEquals("missing command", rejected(() -> McpServerDefinition.from(
        request("files", "stdio", null, null, "-y x", null, null))));
    assertEquals("missing command", rejected(() -> McpServerDefinition.from(
        request("files", "stdio", null, "   ", "-y x", null, null))));
  }

  @Test
  void aNetworkServerWithoutAUrlIsRefused() {
    assertEquals("missing url", rejected(() -> McpServerDefinition.from(
        request("tools", "http", "  ", null, null, null, null))));
    assertEquals("missing url", rejected(() -> McpServerDefinition.from(
        request("tools", "sse", null, null, null, null, null))));
  }

  /**
   * The invariant the config editor now relies on instead of re-deriving it: a definition
   * never carries the other transport\'s fields, so the editor only has to clear the keys a
   * previous edit may have left behind.
   */
  @Test
  void aDefinitionNeverCarriesTheOtherTransportsFields() {
    McpServerDefinition network = McpServerDefinition.from(request(
        "tools", "http", "https://tools.test/mcp", "npx", "-y ignored", Map.of("A", "b"),
        Map.of("ROOT", "/data")));
    assertNull(network.command(), "a network definition kept a command");
    assertTrue(network.args().isEmpty(), "a network definition kept args");
    assertNull(network.environment(), "a network definition kept a stdio environment");

    McpServerDefinition stdio = McpServerDefinition.from(request(
        "files", "stdio", "https://ignored.test/mcp", "npx", null, Map.of("A", "b"),
        Map.of("ROOT", "/data")));
    assertNull(stdio.url(), "a stdio definition kept a url");
    assertNull(stdio.headers(), "a stdio definition kept network headers");
  }

  @Test
  void aBlankOrNullServerNameIsRejectedAndAValidOneIsTrimmed() {
    assertEquals("missing server name", rejected(() -> McpServerDefinition.from(
        request(null, "http", "https://tools.test/mcp", null, null, null, null))));
    assertEquals("missing server name", rejected(() -> McpServerDefinition.from(
        request("   ", "http", "https://tools.test/mcp", null, null, null, null))));
    assertEquals("files", McpServerDefinition.from(
        request("  files  ", "http", "https://tools.test/mcp", null, null, null, null)).name());
  }

  // ── args ──────────────────────────────────────────────────────

  @Test
  void quotedArgsKeepTheirInternalSpaces() {
    assertEquals(
        List.of("--flag", "two words", "and more", "tail"),
        McpServerDefinition.splitArgs("--flag \'two words\' \"and more\"   tail"));
    // an unterminated quote still yields the trailing token rather than dropping it
    assertEquals(List.of("--flag", "two words"), McpServerDefinition.splitArgs("--flag \'two words"));
    assertEquals(List.of(), McpServerDefinition.splitArgs("   "));
    assertEquals(List.of(), McpServerDefinition.splitArgs(null));
  }

  @Test
  void aStdioDefinitionTokenizesTheArgsItWasGiven() {
    assertEquals(List.of("-y", "@scope/files"), McpServerDefinition.from(
        request("files", "stdio", null, "npx", "-y @scope/files", null, null)).args());
  }

  // ── header and environment validation ─────────────────────────

  @Test
  void aHeaderWithNoNameOrNoValueIsRefused() {
    // these are written verbatim into config.yaml and sent on every MCP request
    assertEquals("MCP header name must not be blank", rejected(() -> http(Map.of("   ", "v"))));
    assertEquals("missing value for MCP header: X-Api-Key",
        rejected(() -> http(nullValued("X-Api-Key"))));
  }

  @Test
  void aHeaderCarryingALineBreakIsRefusedInEitherHalf() {
    // header injection: a CR or LF in either half would forge extra request headers
    assertEquals("MCP headers must not contain line breaks",
        rejected(() -> http(Map.of("X-Api-Key", "value\r\nX-Injected: yes"))));
    assertEquals("MCP headers must not contain line breaks",
        rejected(() -> http(Map.of("X-Api\nKey", "value"))));
    assertEquals("MCP headers must not contain line breaks",
        rejected(() -> http(Map.of("X-Evil\r\nInjected", "v"))));
  }

  @Test
  void aValidHeaderIsKeptTrimmedAndVerbatim() {
    assertEquals(Map.of("X-Api-Key", "secret"), http(Map.of("  X-Api-Key  ", "secret")).headers());
  }

  @Test
  void anEnvironmentKeyMustLookLikeAShellVariable() {
    assertEquals("invalid MCP environment key: 1BAD", rejected(() -> stdio(Map.of("1BAD", "v"))));
    assertEquals("invalid MCP environment key: MY-KEY", rejected(() -> stdio(Map.of("MY-KEY", "v"))));
    assertEquals("invalid MCP environment key: ", rejected(() -> stdio(Map.of("   ", "v"))));
    assertEquals("invalid MCP environment key: HAS-DASH",
        rejected(() -> stdio(Map.of("HAS-DASH", "v"))));
    assertEquals("invalid MCP environment key: 1LEADING_DIGIT",
        rejected(() -> stdio(Map.of("1LEADING_DIGIT", "v"))));
  }

  @Test
  void anEnvironmentValueCannotCarryNulOrALineBreak() {
    assertEquals("missing value for MCP environment: ROOT",
        rejected(() -> stdio(nullValued("ROOT"))));
    // a value carrying a line break would inject a second entry into the env block
    assertEquals("MCP environment values must not contain NUL or line breaks",
        rejected(() -> stdio(Map.of("ROOT", "/data\nEXTRA=1"))));
    assertEquals("MCP environment values must not contain NUL or line breaks",
        rejected(() -> stdio(Map.of("ROOT", "/data\u0000"))));
    assertEquals("MCP environment values must not contain NUL or line breaks",
        rejected(() -> stdio(Map.of("TOKEN", "a\nEVIL=1"))));
  }

  @Test
  void aValidEnvironmentEntryIsKept() {
    assertEquals(Map.of("ROOT", "/data"), stdio(Map.of("ROOT", "/data")).environment());
  }

  /**
   * The distinction the editor depends on to leave an advanced field alone: null is "not
   * edited", an empty map is a deliberate clear. Both survive validation.
   */
  @Test
  void anOmittedMapStaysNullWhileAnEmptyOneStaysEmpty() {
    assertNull(http(null).headers());
    assertEquals(Map.of(), http(Map.of()).headers());
    assertNull(stdio(null).environment());
    assertEquals(Map.of(), stdio(Map.of()).environment());
  }
}
