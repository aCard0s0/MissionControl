package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.util.List;

/**
 * A catalog row rendered for the API, with every secret redacted.
 *
 * <p>Split out of {@link McpRegistryService} so that the one place a stored definition
 * becomes an outbound payload is a single method — the redaction is not something to
 * rediscover in each caller.
 */
class McpServerDtoMapper {

  private final McpConfigStore configs;

  McpServerDtoMapper(McpConfigStore configs) {
    this.configs = configs;
  }

  McpServerDto toDto(ServerRow row) {
    StoredConfig config = configs.read(row);
    List<SupportServiceDto> supports = config.supportServices().stream()
        .map(value -> new SupportServiceDto(value.name(), value.image(), value.platform(),
            value.entrypoint(), value.command(), configs.redact(value.environment()),
            value.volumes(), value.healthcheck()))
        .toList();
    return new McpServerDto(row.id(), row.name(), row.description(), row.kind(), row.hostId(), row.serviceKey(),
        config.transport(), config.url(), connectionUrl(row, config), config.image(), config.platform(),
        config.entrypoint(), config.command(), config.stdioCommand(), config.args(), config.internalPort(),
        config.publishedPort(), config.path(), config.crossHostUrl(), configs.redact(config.environment()),
        configs.redact(config.headers()), config.volumes(), config.healthcheck(), supports,
        row.desiredState(), row.runtimeState(), row.operationState(), row.operationError(),
        row.revision(), row.appliedRevision(), row.revision() > row.appliedRevision(),
        row.checkStatus(), row.checkError(), row.checkedAt(), row.latencyMs(), row.createdAt(), row.updatedAt());
  }

  /** How an Agent on the same host reaches this server: a managed one by its Compose
   *  service name, an external one by the URL it was registered with, a stdio one not at all. */
  static String connectionUrl(ServerRow row, StoredConfig config) {
    if (!"managed".equals(row.kind())) return "external".equals(row.kind()) ? config.url() : null;
    return "http://" + row.serviceKey() + ":" + config.internalPort() + config.path();
  }
}
