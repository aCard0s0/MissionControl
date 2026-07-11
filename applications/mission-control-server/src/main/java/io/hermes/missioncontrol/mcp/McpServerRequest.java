package io.hermes.missioncontrol.mcp;

import java.util.List;

/**
 * Create/update input for all catalog kinds. Fields that do not apply to the
 * selected kind must be omitted; the service validates that invariant.
 */
public record McpServerRequest(
    String name,
    String description,
    String kind,
    String hostId,
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
    List<ConfigValueInput> environment,
    List<ConfigValueInput> headers,
    List<VolumeSpec> volumes,
    HealthcheckSpec healthcheck,
    List<SupportServiceRequest> supportServices) {}
