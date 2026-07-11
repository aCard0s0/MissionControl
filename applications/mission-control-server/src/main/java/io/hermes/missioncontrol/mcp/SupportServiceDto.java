package io.hermes.missioncontrol.mcp;

import java.util.List;

/** Safe API view of a managed server's private dependency. */
public record SupportServiceDto(
    String name,
    String image,
    String platform,
    List<String> entrypoint,
    List<String> command,
    List<ConfigValueDto> environment,
    List<VolumeSpec> volumes,
    HealthcheckSpec healthcheck) {}
