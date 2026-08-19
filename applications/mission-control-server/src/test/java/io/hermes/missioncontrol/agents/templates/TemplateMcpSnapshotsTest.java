package io.hermes.missioncontrol.agents.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.mcp.McpRegistryService;
import io.hermes.missioncontrol.mcp.McpServerDto;
import io.hermes.missioncontrol.secrets.SecretCipher;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What an MCP entry becomes when a template is saved.
 *
 * <p>Two rules decide whether credentials leak between definitions. A catalog entry is copied
 * into an independent encrypted snapshot, so a later catalog change cannot silently alter what
 * the template deploys. And because the editor never receives encrypted values, a prior
 * snapshot's secrets are carried forward <em>only</em> while its connection definition is
 * unchanged — pointing an entry at a different server must not inherit the old credentials.
 */
class TemplateMcpSnapshotsTest {

  private final McpRegistryService registry = mock(McpRegistryService.class);
  private final SecretCipher cipher = new SecretCipher("unit-test-key", "", true);
  private final TemplateSecrets secrets = new TemplateSecrets(new SecretsAtRest(cipher));
  private final TemplateMcpSnapshots snapshots = new TemplateMcpSnapshots(registry, secrets);

  // ── custom entries and carried-forward secrets ──────────────────────────

  @Test
  void anUnchangedCustomEntryKeepsThePriorSnapshotsSecrets() {
    // the editor posts back the entry with no values, because it never received them; dropping
    // the stored secrets here would silently unauthenticate the template
    McpServerSpec prior = withSecrets(spec("files", "http", "http://files:1/mcp", null, null));
    McpServerSpec resubmitted = spec("files", "http", "http://files:1/mcp", null, null);

    McpServerSpec stored = snapshots.materialize(List.of(resubmitted), templateWith(prior)).getFirst();

    assertEquals("HEADER", stored.headers().getFirst().key());
    assertEquals("secret", secrets.decryptValues(stored.headers()).get("HEADER"));
    assertEquals("secret", secrets.decryptValues(stored.environment()).get("ENV_KEY"));
  }

  @Test
  void changingAnyPartOfTheConnectionDropsTheInheritedSecrets() {
    // same alias, different server: inheriting the old credentials would send them somewhere the
    // operator never authorised
    McpServerSpec prior = withSecrets(spec("files", "http", "http://files:1/mcp", "npx", "-y a"));

    for (McpServerSpec changed : List.of(
        spec("files", "sse", "http://files:1/mcp", "npx", "-y a"),
        spec("files", "http", "http://elsewhere:1/mcp", "npx", "-y a"),
        spec("files", "http", "http://files:1/mcp", "uvx", "-y a"),
        spec("files", "http", "http://files:1/mcp", "npx", "-y b"))) {
      McpServerSpec stored = snapshots.materialize(List.of(changed), templateWith(prior)).getFirst();
      assertNull(stored.headers(), "headers must not survive a connection change");
      assertNull(stored.environment(), "environment must not survive a connection change");
    }
  }

  @Test
  void anEntryWithNoPriorSnapshotStartsWithNoSecrets() {
    McpServerSpec stored = snapshots
        .materialize(List.of(spec("new", "http", "http://x:1/mcp", null, null)), null).getFirst();

    assertNull(stored.headers());
    assertNull(stored.environment());
    verifyNoInteractions(registry);
  }

  @Test
  void nullsAndAbsentListsAreToleratedOnBothSides() {
    // a template saved by an older client, or one whose entries were cleared
    assertTrue(snapshots.materialize(null, null).isEmpty());
    assertTrue(snapshots.materialize(List.of(), templateWith((McpServerSpec) null)).isEmpty());
    assertEquals(1, snapshots.materialize(
        Arrays.asList(null, spec("files", "http", "http://x:1/mcp", null, null)), null).size());
    // a prior entry with no name cannot be matched against, and must not blow up the save
    assertEquals(1, snapshots.materialize(
        List.of(spec("files", "http", "http://x:1/mcp", null, null)),
        templateWith(spec(null, "http", "http://x:1/mcp", null, null))).size());
  }

  // ── catalog snapshots ───────────────────────────────────────────────────

  @Test
  void aCatalogIdIsResolvedIntoADetachedCopyWithItsHeadersCapturedNow() {
    catalogIs(external("Tools", "http", "https://tools.test/mcp"));
    when(registry.materializedHeaders("mcp-1")).thenReturn(Map.of("Authorization", "Bearer live"));

    McpServerSpec stored = snapshots.materialize(List.of(fromCatalog("mcp-1", "tools")), null).getFirst();

    assertEquals("tools", stored.name());
    assertEquals("https://tools.test/mcp", stored.url());
    assertEquals("Bearer live", secrets.decryptValues(stored.headers()).get("Authorization"));
    // the snapshot is independent of the catalog from here on
    assertNull(stored.sourceServerId());
    assertTrue(stored.environment().isEmpty(), "environment belongs to stdio entries only");
  }

  @Test
  void aManagedCatalogServerPrefersTheAddressAnAgentOnAnotherHostCanReach() {
    McpServerDto source = external("Tools", "http", "http://fallback:1/mcp");
    when(source.connectionUrl()).thenReturn("http://mcp-tools:1100/mcp");
    when(source.crossHostUrl()).thenReturn("https://peer.test/mcp");
    catalogIs(source);

    assertEquals("https://peer.test/mcp", stored("mcp-1").url());

    // then the same-host service name, then whatever the record was registered with
    when(source.crossHostUrl()).thenReturn("   ");
    assertEquals("http://mcp-tools:1100/mcp", stored("mcp-1").url());
    when(source.connectionUrl()).thenReturn(null);
    assertEquals("http://fallback:1/mcp", stored("mcp-1").url());
  }

  @Test
  void aCatalogServerWithNoReachableAddressIsRefused() {
    McpServerDto source = external("Tools", "http", null);
    when(source.connectionUrl()).thenReturn(null);
    when(source.crossHostUrl()).thenReturn(null);
    catalogIs(source);

    assertEquals("catalog server has no usable connection URL: Tools",
        assertThrows(IllegalArgumentException.class,
            () -> stored("mcp-1")).getMessage());
  }

  @Test
  void aStdioCatalogServerCapturesItsCommandArgsAndEnvironment() {
    catalogIs(stdio("Files", "npx", List.of("-y", "@example/files")));
    when(registry.materializedEnvironment("mcp-1")).thenReturn(Map.of("ROOT", "/data"));

    McpServerSpec stored = stored("mcp-1");

    assertEquals("stdio", stored.transport());
    assertEquals("npx", stored.command());
    assertEquals("-y @example/files", stored.args());
    assertEquals("/data", secrets.decryptValues(stored.environment()).get("ROOT"));
    assertTrue(stored.headers().isEmpty(), "headers belong to HTTP entries only");
    assertNull(stored.url());
  }

  @Test
  void aStdioCatalogServerWithNoCommandIsRefused() {
    catalogIs(stdio("Files", "   ", List.of()));

    assertEquals("catalog stdio server has no command: Files",
        assertThrows(IllegalArgumentException.class, () -> stored("mcp-1")).getMessage());
  }

  @Test
  void aStdioServerWithNoArgumentsStoresNoArgumentString() {
    catalogIs(stdio("Files", "npx", List.of()));
    assertNull(stored("mcp-1").args());

    catalogIs(stdio("Files", "npx", null));
    assertNull(stored("mcp-1").args());
  }

  @Test
  void catalogArgumentsAreShellQuotedTheSameWayTheAgentPathQuotesThem() {
    // the joined string is executed by the Agent's shell, so an argument with a space or a quote
    // must not become two arguments or close the quoting
    catalogIs(stdio("Files", "npx",
        Arrays.asList("-y", "my project", "it's", "say \"hi\"", "", null, "--flag=a b")));

    assertEquals("-y 'my project' 'it'\"'\"'s' 'say \"hi\"' '' '' '--flag=a b'", stored("mcp-1").args());
  }

  @Test
  void theCatalogAliasFallsBackToTheServersOwnNameAndIsTrimmed() {
    catalogIs(external("Tools", "http", "https://tools.test/mcp"));

    assertEquals("Tools", snapshots.materialize(
        List.of(fromCatalog("mcp-1", "   ")), null).getFirst().name());
    assertEquals("Tools", snapshots.materialize(
        List.of(fromCatalog("mcp-1", null)), null).getFirst().name());
    assertEquals("tools", snapshots.materialize(
        List.of(fromCatalog("mcp-1", "  tools  ")), null).getFirst().name());
  }

  @Test
  void aCatalogEntryIsEnabledUnlessItSaysOtherwise() {
    catalogIs(external("Tools", "http", "https://tools.test/mcp"));

    assertEquals(true, snapshots.materialize(
        List.of(fromCatalog("mcp-1", "tools", null)), null).getFirst().enabled());
    assertEquals(true, snapshots.materialize(
        List.of(fromCatalog("mcp-1", "tools", true)), null).getFirst().enabled());
    assertEquals(false, snapshots.materialize(
        List.of(fromCatalog("mcp-1", "tools", false)), null).getFirst().enabled());
  }

  @Test
  void aCatalogIdIsTrimmedBeforeItIsLookedUp() {
    catalogIs(external("Tools", "http", "https://tools.test/mcp"));

    snapshots.materialize(List.of(fromCatalog("  mcp-1  ", "tools")), null);

    verify(registry).require("mcp-1");
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private McpServerSpec stored(String sourceId) {
    return snapshots.materialize(List.of(fromCatalog(sourceId, "tools")), null).getFirst();
  }

  private void catalogIs(McpServerDto source) {
    when(registry.require("mcp-1")).thenReturn(source);
  }

  private static McpServerDto external(String name, String transport, String url) {
    McpServerDto source = mock(McpServerDto.class);
    when(source.name()).thenReturn(name);
    when(source.kind()).thenReturn("external");
    when(source.transport()).thenReturn(transport);
    when(source.url()).thenReturn(url);
    return source;
  }

  private static McpServerDto stdio(String name, String command, List<String> args) {
    McpServerDto source = mock(McpServerDto.class);
    when(source.name()).thenReturn(name);
    when(source.kind()).thenReturn("STDIO");   // matched case-insensitively
    when(source.stdioCommand()).thenReturn(command);
    when(source.args()).thenReturn(args);
    return source;
  }

  private static McpServerSpec spec(
      String name, String transport, String url, String command, String args) {
    return new McpServerSpec(name, transport, url, command, args, true);
  }

  private McpServerSpec withSecrets(McpServerSpec base) {
    return new McpServerSpec(base.name(), base.transport(), base.url(), base.command(), base.args(),
        base.enabled(), null,
        List.of(new TemplateMcpConfigValue("ENV_KEY", cipher.encrypt("secret"))),
        List.of(new TemplateMcpConfigValue("HEADER", cipher.encrypt("secret"))));
  }

  private static McpServerSpec fromCatalog(String sourceServerId, String alias) {
    return fromCatalog(sourceServerId, alias, true);
  }

  private static McpServerSpec fromCatalog(String sourceServerId, String alias, Boolean enabled) {
    return new McpServerSpec(alias, null, null, null, null, enabled, sourceServerId, null, null);
  }

  private static ProfileTemplate templateWith(McpServerSpec... entries) {
    return new ProfileTemplate("pt-1", "ops", "", "anthropic", "m", "", "", "", "",
        List.of(), Arrays.asList(entries), List.of(), 1L, 1L);
  }
}
