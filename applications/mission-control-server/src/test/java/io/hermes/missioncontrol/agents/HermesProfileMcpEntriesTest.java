package io.hermes.missioncontrol.agents;

import static io.hermes.missioncontrol.agents.FakeContainer.CONTAINER;
import static io.hermes.missioncontrol.agents.FakeContainer.URL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.agents.api.AddMcpServerRequest;
import io.hermes.missioncontrol.agents.api.AgentMcpServerDto;
import io.hermes.missioncontrol.agents.api.McpTestResult;
import io.hermes.missioncontrol.docker.DockerExecService.ExecResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Reading the {@code mcp_servers} block back, and probing one entry.
 *
 * <p>The block is hand-editable YAML written by hermes, an operator or an agent, so every
 * reader here has to survive a shape it did not expect: a scalar where a map belongs, a
 * transport it does not know, tool filters in either form. The probe's verdict is what the
 * dashboard shows as connected/disabled/error, and its detail line is the only thing an
 * operator gets when a handshake fails.
 */
class HermesProfileMcpEntriesTest {

  private static final String PROFILE = "ops";

  private HermesProfileMcp mcp(FakeContainer container) {
    return new HermesProfileMcp(container.files(), new HermesConfigEditor());
  }

  // ── listing entries ─────────────────────────────────────────────────────

  @Test
  void aConfigWithNoUsableMcpBlockListsNothing() {
    HermesProfileMcp mcp = mcp(new FakeContainer());

    assertTrue(mcp.list(URL, CONTAINER, PROFILE, null).isEmpty());
    assertTrue(mcp.list(URL, CONTAINER, PROFILE, Map.of()).isEmpty());
    // a scalar where the block belongs, which a hand-edit produces easily
    assertTrue(mcp.list(URL, CONTAINER, PROFILE, yaml("mcp_servers: none\n")).isEmpty());
  }

  @Test
  void anEntryWithNoNameOrNoBodyIsSkippedRatherThanRendered() {
    Map<?, ?> config = yaml("""
        mcp_servers:
          '': {transport: http, url: 'http://x:1/mcp'}
          broken: just-a-string
          files: {transport: http, url: 'http://files:1/mcp'}
        """);

    List<AgentMcpServerDto> listed = mcp(new FakeContainer()).list(URL, CONTAINER, PROFILE, config);

    assertEquals(List.of("files"), listed.stream().map(AgentMcpServerDto::name).toList());
  }

  @Test
  void theTransportIsReadFromTheEntryAndStdioIsInferredFromACommand() {
    Map<?, ?> config = yaml("""
        mcp_servers:
          shell: {command: npx, args: ['-y', '@example/files']}
          stream: {transport: http, url: 'http://x:1/mcp'}
          legacy: {transport: sse, url: 'http://x:1/sse'}
          unknown: {transport: grpc, url: 'http://x:1/mcp'}
          silent: {url: 'http://x:1/mcp'}
        """);

    List<AgentMcpServerDto> listed = mcp(new FakeContainer()).list(URL, CONTAINER, PROFILE, config);

    assertEquals("stdio", byName(listed, "shell").transport(), "a command means stdio");
    assertEquals("http", byName(listed, "stream").transport());
    assertEquals("sse", byName(listed, "legacy").transport());
    // anything else is treated as streamable HTTP rather than rejected on a read
    assertEquals("http", byName(listed, "unknown").transport());
    assertEquals("http", byName(listed, "silent").transport());
    assertEquals("-y @example/files", byName(listed, "shell").args());
  }

  @Test
  void onlyAnExplicitFalseDisablesAnEntry() {
    // an absent 'enabled' means enabled; hermes writes the key only when it is off
    Map<?, ?> config = yaml("""
        mcp_servers:
          plain: {transport: http, url: 'http://x:1/mcp'}
          switched-off: {transport: http, url: 'http://x:1/mcp', enabled: false}
          loud: {transport: http, url: 'http://x:1/mcp', enabled: 'FALSE'}
          odd: {transport: http, url: 'http://x:1/mcp', enabled: 'maybe'}
        """);

    List<AgentMcpServerDto> listed = mcp(new FakeContainer()).list(URL, CONTAINER, PROFILE, config);

    assertTrue(byName(listed, "plain").enabled());
    assertEquals(false, byName(listed, "switched-off").enabled());
    assertEquals(false, byName(listed, "loud").enabled(), "the comparison is case-insensitive");
    assertTrue(byName(listed, "odd").enabled(), "anything but 'false' is on");
    assertEquals("disabled", byName(listed, "switched-off").status());
    assertEquals("unknown", byName(listed, "plain").status(), "nothing has probed it yet");
  }

  @Test
  void theToolFilterIsCountedOnlyWhenItIsAnIncludeList() {
    Map<?, ?> config = yaml("""
        mcp_servers:
          filtered: {transport: http, url: 'http://x:1/mcp', tools: {include: [read, write, '  ']}}
          scalar: {transport: http, url: 'http://x:1/mcp', tools: all}
          exclude: {transport: http, url: 'http://x:1/mcp', tools: {exclude: [write]}}
        """);

    List<AgentMcpServerDto> listed = mcp(new FakeContainer()).list(URL, CONTAINER, PROFILE, config);

    assertEquals(2, byName(listed, "filtered").tools(), "blank entries are not tools");
    assertEquals(0, byName(listed, "scalar").tools());
    assertEquals(0, byName(listed, "exclude").tools());
  }

  // ── probing one entry ───────────────────────────────────────────────────

  @Test
  void probingWithoutAServerNameIsRefused() {
    HermesProfileMcp mcp = mcp(new FakeContainer());

    for (String name : List.of("", "   ")) {
      assertEquals("missing server name", assertThrows(IllegalArgumentException.class,
          () -> mcp.test(URL, CONTAINER, PROFILE, name)).getMessage());
    }
    assertEquals("missing server name", assertThrows(IllegalArgumentException.class,
        () -> mcp.test(URL, CONTAINER, PROFILE, null)).getMessage());
  }

  @Test
  void probingAnEntryTheConfigDoesNotHaveReportsThatRatherThanFailing() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  other: {transport: http, url: 'http://x:1/mcp'}\n");

    McpTestResult result = mcp(container).test(URL, CONTAINER, PROFILE, "files");

    assertEquals("error", result.status());
    assertEquals("server not found in config.yaml", result.error());

    // the same when there is no mcp block at all
    FakeContainer empty = new FakeContainer().file(configPath(), "model: opus\n");
    assertEquals("server not found in config.yaml",
        mcp(empty).test(URL, CONTAINER, PROFILE, "files").error());
  }

  @Test
  void probingADisabledEntryReportsItDisabledWithoutRunningAnything() {
    FakeContainer container = new FakeContainer().file(configPath(),
        "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp', enabled: false,"
            + " tools: {include: [read]}}\n");

    McpTestResult result = mcp(container).test(URL, CONTAINER, PROFILE, "files");

    assertEquals("disabled", result.status());
    assertEquals(1, result.tools(), "the configured filter is still reported");
    assertNull(result.latencyMs());
    assertTrue(container.executed().stream().noneMatch(argv -> argv.contains("mcp")),
        "a disabled entry is never probed");
  }

  @Test
  void aSuccessfulProbeReportsConnectedAndTheToolsItDiscovered() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0,
            "[32m✓ Connected (http)[0m\n  discovered 7 tools\n", ""));

    McpTestResult result = mcp(container).test(URL, CONTAINER, PROFILE, "files");

    assertEquals("connected", result.status());
    assertEquals(7, result.tools());
    assertNull(result.error());
    assertTrue(result.latencyMs() >= 0);
  }

  @Test
  void aFailedProbeReportsTheLastLineOfWhatTheCliSaid() {
    // the operator sees this string and nothing else, so it has to be the reason
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(1, "connecting…\n",
            "[31mtraceback\nConnectionRefusedError: [Errno 111] Connection refused[0m\n"));

    McpTestResult result = mcp(container).test(URL, CONTAINER, PROFILE, "files");

    assertEquals("error", result.status());
    assertEquals("ConnectionRefusedError: [Errno 111] Connection refused", result.error());
    assertNull(result.latencyMs());
  }

  @Test
  void aProbeThatSaysNothingAtAllStillReportsAReason() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(1, "  ", "   "));

    assertEquals("MCP handshake failed", mcp(container).test(URL, CONTAINER, PROFILE, "files").error());
  }

  @Test
  void aProbeThatExitsZeroWithoutTheConnectedBannerIsStillAFailure() {
    // hermes exits 0 for a server that answered HTTP but not the MCP handshake
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "no MCP endpoint on that path\n", ""));

    McpTestResult result = mcp(container).test(URL, CONTAINER, PROFILE, "files");

    assertEquals("error", result.status());
    assertEquals("no MCP endpoint on that path", result.error());
  }

  @Test
  void theDefaultProfileIsProbedWithoutAProfileFlag() {
    // 'hermes -p default' is not how the CLI addresses the default profile
    FakeContainer container = new FakeContainer()
        .file("/opt/data/config.yaml",
            "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "✓ Connected (http)\n", ""));

    mcp(container).test(URL, CONTAINER, "default", "files");

    List<String> probe = container.executed().stream()
        .filter(argv -> argv.contains("mcp")).findFirst().orElseThrow();
    assertEquals(List.of("hermes", "mcp", "test", "files"), probe);
  }

  // ── the pure readers behind the probe ───────────────────────────────────

  @Test
  void theToolCountIsTheLargestFigureTheOutputMentions() {
    assertEquals(0, HermesProfileMcp.parseToolCount(null));
    assertEquals(0, HermesProfileMcp.parseToolCount("nothing numeric here"));
    assertEquals(1, HermesProfileMcp.parseToolCount("1 tool available"));
    // several lines mention counts; the largest is the discovered total
    assertEquals(12, HermesProfileMcp.parseToolCount("3 tools filtered\nfound 12 tools\n"));
    assertEquals(9, HermesProfileMcp.parseToolCount("Discovered 9 tools"));
  }

  @Test
  void theConnectedBannerIsRecognisedThroughColourCodes() {
    assertTrue(HermesProfileMcp.mcpProbeSucceeded("[32m  ✓ Connected (sse)[0m"));
    assertTrue(HermesProfileMcp.mcpProbeSucceeded("  ✔ Connected (http)"));
    assertEquals(false, HermesProfileMcp.mcpProbeSucceeded("Connected (http)"),
        "the banner is the marker plus the transport, not the word alone");
    assertEquals(false, HermesProfileMcp.mcpProbeSucceeded(null));
  }

  // ── cache eviction ──────────────────────────────────────────────────────

  @Test
  void evictingAProfileDropsOnlyItsOwnProbes() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .file("/opt/data/profiles/other/config.yaml",
            "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "✓ Connected (http)\n", ""));
    HermesProfileMcp mcp = mcp(container);
    mcp.test(URL, CONTAINER, PROFILE, "files");
    mcp.test(URL, CONTAINER, "other", "files");

    mcp.evictProfile(URL, CONTAINER, PROFILE);

    // the evicted profile reports unknown again; the other keeps its cached verdict
    assertEquals("unknown", byName(mcp.list(URL, CONTAINER, PROFILE, cachedConfig()), "files").status());
    assertEquals("connected", byName(mcp.list(URL, CONTAINER, "other", cachedConfig()), "files").status());
  }

  // ── fixtures ────────────────────────────────────────────────────────────

  private static String configPath() {
    return "/opt/data/profiles/" + PROFILE + "/config.yaml";
  }

  private static Map<?, ?> cachedConfig() {
    return yaml("mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n");
  }

  private static AgentMcpServerDto byName(List<AgentMcpServerDto> listed, String name) {
    return listed.stream().filter(s -> name.equals(s.name())).findFirst().orElseThrow();
  }

  private static Map<?, ?> yaml(String text) {
    return (Map<?, ?>) new Yaml().load(text);
  }

  // ── config edits ────────────────────────────────────────────────────────

  @Test
  void addingAServerWritesItIntoTheConfigAndInvalidatesItsProbe() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "✓ Connected (http)\n", ""));
    HermesProfileMcp mcp = mcp(container);
    mcp.test(URL, CONTAINER, PROFILE, "files");

    mcp.add(URL, CONTAINER, PROFILE, new AddMcpServerRequest(
        "docs", "http", "http://docs:1/mcp", null, null, true));

    String written = writtenConfig(container);
    assertTrue(written.contains("docs"), written);
    assertTrue(written.contains("files"), "the existing entry survives the edit");
  }

  @Test
  void renamingAnEntryInvalidatesBothNamesProbes() {
    // the probe filed under the old name describes a definition that no longer exists, and the
    // new name must not inherit it
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "✓ Connected (http)\n", ""));
    HermesProfileMcp mcp = mcp(container);
    mcp.test(URL, CONTAINER, PROFILE, "files");

    mcp.update(URL, CONTAINER, PROFILE, "files", new AddMcpServerRequest(
        "files-v2", "http", "http://x:1/mcp", null, null, true));

    Map<?, ?> after = yaml(writtenConfig(container));
    Map<?, ?> servers = (Map<?, ?>) after.get("mcp_servers");
    assertTrue(servers.containsKey("files-v2"), writtenConfig(container));
    assertEquals(false, servers.containsKey("files"));
    assertEquals("unknown",
        byName(mcp.list(URL, CONTAINER, PROFILE, after), "files-v2").status());
  }

  @Test
  void togglingAnEntryKeepsEverythingElseInItsDefinition() {
    // URL, command, args, tool filters and any key hermes knows that we do not
    FakeContainer container = new FakeContainer().file(configPath(),
        "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp', unknown_key: keep-me}\n");

    mcp(container).setEnabled(URL, CONTAINER, PROFILE, "files", false);

    Map<?, ?> servers = (Map<?, ?>) yaml(writtenConfig(container)).get("mcp_servers");
    Map<?, ?> entry = (Map<?, ?>) servers.get("files");
    assertEquals(false, entry.get("enabled"));
    assertEquals("keep-me", entry.get("unknown_key"), "an unmodelled key is not dropped");
    assertEquals("http://x:1/mcp", entry.get("url"));
  }

  @Test
  void removingAnEntryTakesItOutOfTheConfigEntirely() {
    FakeContainer container = new FakeContainer().file(configPath(),
        "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n"
            + "  docs: {transport: http, url: 'http://docs:1/mcp'}\n");

    mcp(container).remove(URL, CONTAINER, PROFILE, "files");

    Map<?, ?> servers = (Map<?, ?>) yaml(writtenConfig(container)).get("mcp_servers");
    assertEquals(false, servers.containsKey("files"));
    assertTrue(servers.containsKey("docs"));
  }

  /** The config content the collaborator wrote back, taken from the write argv. */
  private static String writtenConfig(FakeContainer container) {
    return container.executed().stream()
        .filter(argv -> argv.size() > 2 && argv.get(2).contains("printf"))
        .reduce((first, second) -> second)
        .orElseThrow()
        .getLast();
  }

  @Test
  void aProbeThatOnlyWroteToStdoutStillYieldsItsLastLine() {
    // hermes reports some failures on stdout with an empty stderr
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(1, "connecting…\nno route to host\n", "   "));

    assertEquals("no route to host", mcp(container).test(URL, CONTAINER, PROFILE, "files").error());
  }

  @Test
  void aVeryLongProbeFailureIsTruncatedSoOneErrorCannotFloodTheResponse() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(1, "", "x".repeat(500)));

    String error = mcp(container).test(URL, CONTAINER, PROFILE, "files").error();

    assertEquals(300, error.length(), "the whole message is serialised into the API response");
  }

  @Test
  void trailingBlankLinesDoNotHideTheReason() {
    // the CLI ends its output with a newline and sometimes a spinner remnant; taking the literal
    // last line would report an empty reason
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(1, "", "the real reason\n\n   \n"));

    assertEquals("the real reason", mcp(container).test(URL, CONTAINER, PROFILE, "files").error());
  }

  @Test
  void evictingAContainerDropsEveryProfilesProbesForIt() {
    FakeContainer container = new FakeContainer()
        .file(configPath(), "mcp_servers:\n  files: {transport: http, url: 'http://x:1/mcp'}\n")
        .onCommand("mcp", new ExecResult(0, "✓ Connected (http)\n", ""));
    HermesProfileMcp mcp = mcp(container);
    mcp.test(URL, CONTAINER, PROFILE, "files");

    // a different container id keeps its own probes; ours are dropped
    mcp.evictProfile(URL, "some-other-container", PROFILE);
    assertEquals("connected", byName(mcp.list(URL, CONTAINER, PROFILE, cachedConfig()), "files").status());

    mcp.evictProfile("unix:///other-daemon", CONTAINER, PROFILE);
    assertEquals("connected", byName(mcp.list(URL, CONTAINER, PROFILE, cachedConfig()), "files").status());

    mcp.evictProfile(URL, CONTAINER, PROFILE);
    assertEquals("unknown", byName(mcp.list(URL, CONTAINER, PROFILE, cachedConfig()), "files").status());
  }
}
