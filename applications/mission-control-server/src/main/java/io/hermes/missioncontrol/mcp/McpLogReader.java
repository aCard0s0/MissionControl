package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The log tail of a managed MCP server, merged across its support services.
 *
 * <p>Split out of {@link McpRegistryService} because a managed record is often several
 * containers — a server plus, say, its private database — and only a single interleaved,
 * service-labelled stream is useful for diagnosing one.
 */
class McpLogReader {

  private final HostService hosts;
  private final DockerGateway docker;
  private final ComposeStackManager compose;
  private final McpConfigStore configs;

  McpLogReader(
      HostService hosts, DockerGateway docker, ComposeStackManager compose, McpConfigStore configs) {
    this.hosts = hosts;
    this.docker = docker;
    this.compose = compose;
    this.configs = configs;
  }

  List<LogLineDto> logs(ServerRow row, int tail) {
    if (!"managed".equals(row.kind())) {
      throw new IllegalArgumentException("logs are available only for managed MCP servers");
    }
    StoredConfig config = configs.read(row);
    List<String> serviceNames = new ArrayList<>();
    serviceNames.add(row.serviceKey());
    for (StoredSupportService support : config.supportServices()) {
      serviceNames.add(ComposeStackRenderer.supportKey(row.serviceKey(), support.name()));
    }
    List<LogLineDto> result = new ArrayList<>();
    String url = hosts.urlOf(row.hostId());
    for (String serviceName : serviceNames) {
      String containerId = compose.serviceContainerId(row.hostId(), serviceName);
      if (containerId == null) continue;
      for (LogLineDto line : docker.logs(url, containerId, Math.min(Math.max(tail, 1), 500))) {
        result.add(new LogLineDto(line.ts(), line.level(), serviceName, line.msg()));
      }
    }
    result.sort(Comparator.comparingLong(LogLineDto::ts));
    return result;
  }
}
