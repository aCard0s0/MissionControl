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
    List<StoredSupportService> supportServices) {}
