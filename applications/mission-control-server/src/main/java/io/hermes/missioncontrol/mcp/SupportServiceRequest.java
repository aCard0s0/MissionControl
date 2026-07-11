package io.hermes.missioncontrol.mcp;

import java.util.List;

/** A private Compose dependency of a managed MCP server. */
public record SupportServiceRequest(
    String name,
    String image,
    String platform,
    List<String> entrypoint,
    List<String> command,
    List<ConfigValueInput> environment,
    List<VolumeSpec> volumes,
    HealthcheckSpec healthcheck) {}
