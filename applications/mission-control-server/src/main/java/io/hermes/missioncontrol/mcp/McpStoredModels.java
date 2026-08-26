package io.hermes.missioncontrol.mcp;

import java.util.List;

record StoredValue(String key, String value, boolean secret) {}

record StoredSupportService(
    String name,
    String image,
    String platform,
    List<String> entrypoint,
    List<String> command,
    List<StoredValue> environment,
    List<VolumeSpec> volumes,
    HealthcheckSpec healthcheck) {}

/**
 * The stored form of an MCP definition, as it sits in {@code mcp_servers.config_json}.
 *
 * <p>Its collections are never null, and that is enforced here rather than by the code that
 * reads the column. Records deserialize through their canonical constructor, so Jackson gets the
 * same guarantee as a hand-built one — {@code McpCatalogSeeder.repairSeeds} calls
 * {@code entrypoint().isEmpty()} straight off a read, and used to be relying on
 * {@code McpConfigStore.read} restating all seventeen fields to defend seven of them.
 */
record StoredConfig(
    String transport,
    String url,
    String image,
    String platform,
    List<String> entrypoint,
    List<String> command,
    String stdioCommand,
    List<String> args,
    Integer internalPort,
    Integer publishedPort,
    String path,
    String crossHostUrl,
    List<StoredValue> environment,
    List<StoredValue> headers,
    List<VolumeSpec> volumes,
    HealthcheckSpec healthcheck,
    List<StoredSupportService> supportServices) {

  StoredConfig {
    entrypoint = list(entrypoint);
    command = list(command);
    args = list(args);
    environment = list(environment);
    headers = list(headers);
    volumes = list(volumes);
    supportServices = list(supportServices);
  }

  private static <T> List<T> list(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
