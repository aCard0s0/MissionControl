package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A managed MCP record is often several containers — the server plus its private support
 * services — so its log tail is a merge. Each line has to carry the service it came from and
 * the merged stream has to be in time order, or a diagnosis reads the wrong container's output.
 */
class McpLogReaderTest {

  private static final String URL = "unix:///sock";
  private static final DockerHostRef HOST_REF = new DockerHostRef("dh-local", URL);
  private static final String SERVICE = "mcp-files";
  private static final String SUPPORT = ComposeStackRenderer.supportKey(SERVICE, "db");

  private HostService hosts;
  private DockerGateway docker;
  private ComposeStackManager compose;
  private McpConfigStore configs;
  private McpLogReader reader;

  @BeforeEach
  void setUp() {
    hosts = mock(HostService.class);
    docker = mock(DockerGateway.class);
    compose = mock(ComposeStackManager.class);
    configs = mock(McpConfigStore.class);
    reader = new McpLogReader(hosts, docker, compose, configs);
    when(hosts.ref("dh-local")).thenReturn(new DockerHostRef("dh-local", URL));
  }

  @Test
  void anExternalOrStdioServerHasNoLogsToRead() {
    // there is no container behind either kind, so this is a 400 rather than an empty tail that
    // reads as "the server is quiet"
    ServerRow external = row("external");

    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> reader.logs(external, 100));

    assertEquals("logs are available only for managed MCP servers", failure.getMessage());
    verify(hosts, never()).ref(anyString());
  }

  @Test
  void theSupportServicesLogsAreMergedInTimeOrderAndLabelledByService() {
    when(configs.read(any())).thenReturn(config("db"));
    when(compose.serviceContainerId("dh-local", SERVICE)).thenReturn("cid-server");
    when(compose.serviceContainerId("dh-local", SUPPORT)).thenReturn("cid-db");
    when(docker.logs(HOST_REF, "cid-server", 100, null)).thenReturn(List.of(
        new LogLineDto(30, "INFO", "cid-server", "listening on 1100"),
        new LogLineDto(10, "INFO", "cid-server", "starting")));
    when(docker.logs(HOST_REF, "cid-db", 100, null)).thenReturn(List.of(
        new LogLineDto(20, "INFO", "cid-db", "database system is ready")));

    List<LogLineDto> lines = reader.logs(row("managed"), 100);

    assertEquals(List.of(10L, 20L, 30L), lines.stream().map(LogLineDto::ts).toList());
    // the source is rewritten from the container id to the service key: 'mcp-files' means
    // something to the operator reading the pane, a container id does not
    assertEquals(List.of(SERVICE, SUPPORT, SERVICE), lines.stream().map(LogLineDto::source).toList());
    assertEquals("database system is ready", lines.get(1).msg());
  }

  @Test
  void aServiceWithNoContainerYetIsSkippedRatherThanFailingTheWholeTail() {
    // one support service can be down or not yet created while the server itself is running
    when(configs.read(any())).thenReturn(config("db"));
    when(compose.serviceContainerId("dh-local", SERVICE)).thenReturn("cid-server");
    when(compose.serviceContainerId("dh-local", SUPPORT)).thenReturn(null);
    when(docker.logs(HOST_REF, "cid-server", 100, null))
        .thenReturn(List.of(new LogLineDto(10, "INFO", "cid-server", "starting")));

    List<LogLineDto> lines = reader.logs(row("managed"), 100);

    assertEquals(1, lines.size());
    verify(docker, never()).logs(HOST_REF, null, 100, null);
  }

  @Test
  void theRequestedTailIsClampedToAUsableRange() {
    when(configs.read(any())).thenReturn(config());
    when(compose.serviceContainerId("dh-local", SERVICE)).thenReturn("cid-server");
    when(docker.logs(any(), anyString(), anyInt(), any())).thenReturn(List.of());

    reader.logs(row("managed"), 0);
    reader.logs(row("managed"), -5);
    reader.logs(row("managed"), 5_000);
    reader.logs(row("managed"), 250);

    // 0 and negatives would ask the daemon for everything it has; 5000 lines is a response
    // nobody reads and a stream the dashboard has to hold in memory
    verify(docker, times(2)).logs(HOST_REF, "cid-server", 1, null);
    verify(docker).logs(HOST_REF, "cid-server", 500, null);
    verify(docker).logs(HOST_REF, "cid-server", 250, null);
  }

  @Test
  void aServerWithNoSupportServicesReadsOnlyItsOwnContainer() {
    when(configs.read(any())).thenReturn(config());
    when(compose.serviceContainerId("dh-local", SERVICE)).thenReturn("cid-server");
    when(docker.logs(HOST_REF, "cid-server", 100, null))
        .thenReturn(List.of(new LogLineDto(10, "INFO", "cid-server", "starting")));

    List<LogLineDto> lines = reader.logs(row("managed"), 100);

    assertTrue(lines.stream().allMatch(line -> SERVICE.equals(line.source())));
    verify(compose).serviceContainerId("dh-local", SERVICE);
    verify(compose, never()).serviceContainerId("dh-local", SUPPORT);
  }

  private static ServerRow row(String kind) {
    return new ServerRow("srv-1", "Files", null, null, kind, "dh-local", SERVICE, "{}",
        "running", "running", "idle", null, 1L, 1L, null, "ok", null, null, null, 0L, 0L);
  }

  private static StoredConfig config(String... supportServices) {
    List<StoredSupportService> supports = Arrays.stream(supportServices)
        .map(name -> new StoredSupportService(name, "postgres:17", null,
            List.of(), List.of(), List.of(), List.of(), null))
        .toList();
    return new StoredConfig("http", null, "example/files:1", null, List.of(), List.of(), null,
        List.of(), 1100, null, "/mcp", null, List.of(), List.of(), List.of(), null, supports);
  }
}
