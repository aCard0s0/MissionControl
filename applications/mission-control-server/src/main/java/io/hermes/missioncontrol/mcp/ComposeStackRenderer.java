package io.hermes.missioncontrol.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders the deliberately narrow managed-server model into non-secret Compose YAML.
 *
 * <p>Every name this emits that something else has to recognise — the project, the network, the
 * ownership labels — comes from {@link ManagedMcpStack}, because the reader is always somewhere
 * else: {@link ComposeStackManager} inspects the labels back, {@link McpHealthProbe} and the
 * {@code agents} package join the network.
 */
@Component
final class ComposeStackRenderer {

  record Deployment(
      String id,
      String serviceKey,
      StoredConfig config,
      Map<String, String> mainEnvironment,
      Map<String, Map<String, String>> supportEnvironment) {}

  record Rendered(
      String yaml,
      Map<String, String> processEnvironment,
      Map<String, List<String>> serviceNames,
      Map<String, List<String>> volumeNames) {}

  Rendered render(List<Deployment> deployments) {
    StringBuilder yaml = new StringBuilder(deployments.isEmpty() ? "services: {}\n" : "services:\n");
    Map<String, String> processEnvironment = new LinkedHashMap<>();
    Map<String, List<String>> serviceNames = new LinkedHashMap<>();
    Map<String, List<String>> volumeNames = new LinkedHashMap<>();
    Map<String, String> declaredVolumeKeys = new LinkedHashMap<>();

    for (Deployment deployment : deployments) {
      StoredConfig config = deployment.config();
      List<String> services = new ArrayList<>();
      List<String> actualVolumes = new ArrayList<>();

      for (StoredSupportService support : config.supportServices()) {
        String supportKey = supportKey(deployment.serviceKey(), support.name());
        services.add(supportKey);
        appendService(yaml, deployment, supportKey, support.image(), support.platform(),
            support.entrypoint(), support.command(),
            deployment.supportEnvironment().getOrDefault(support.name(), Map.of()),
            support.volumes(), support.healthcheck(), Map.of(), processEnvironment,
            declaredVolumeKeys, actualVolumes);
      }

      services.addFirst(deployment.serviceKey());
      Map<String, Boolean> dependencies = new LinkedHashMap<>();
      for (StoredSupportService support : config.supportServices()) {
        boolean healthy = support.healthcheck() != null
            && !"NONE".equals(support.healthcheck().test().getFirst());
        dependencies.put(supportKey(deployment.serviceKey(), support.name()), healthy);
      }
      appendService(yaml, deployment, deployment.serviceKey(), config.image(), config.platform(),
          config.entrypoint(), config.command(), deployment.mainEnvironment(), config.volumes(),
          config.healthcheck(), dependencies, processEnvironment, declaredVolumeKeys, actualVolumes);
      if (config.internalPort() != null) {
        yaml.append("    expose:\n      - '").append(config.internalPort()).append("'\n");
      }
      if (config.publishedPort() != null) {
        yaml.append("    ports:\n      - '").append(config.publishedPort()).append(":")
            .append(config.internalPort()).append("'\n");
      }
      serviceNames.put(deployment.id(), List.copyOf(services));
      volumeNames.put(deployment.id(), List.copyOf(new LinkedHashSet<>(actualVolumes)));
    }

    yaml.append("networks:\n  mcp:\n    name: '").append(ManagedMcpStack.NETWORK).append("'\n")
        .append("    driver: bridge\n")
        .append("    labels:\n")
        .append("      " + ManagedMcpStack.OWNER_LABEL + ": '").append(ManagedMcpStack.PROJECT).append("'\n");
    if (!declaredVolumeKeys.isEmpty()) {
      yaml.append("volumes:\n");
      for (Map.Entry<String, String> volume : declaredVolumeKeys.entrySet()) {
        String volumeKey = volume.getKey();
        yaml.append("  ").append(volumeKey).append(":\n")
            .append("    name: '").append(actualVolumeName(volumeKey)).append("'\n")
            .append("    labels:\n")
            .append("      " + ManagedMcpStack.OWNER_LABEL + ": '").append(ManagedMcpStack.PROJECT).append("'\n")
            .append("      " + ManagedMcpStack.SERVER_ID_LABEL + ": ").append(quote(volume.getValue())).append("\n");
      }
    }
    return new Rendered(yaml.toString(), Map.copyOf(processEnvironment),
        Map.copyOf(serviceNames), Map.copyOf(volumeNames));
  }

  private static void appendService(
      StringBuilder yaml,
      Deployment deployment,
      String serviceKey,
      String image,
      String platform,
      List<String> entrypoint,
      List<String> command,
      Map<String, String> environment,
      List<VolumeSpec> volumes,
      HealthcheckSpec healthcheck,
      Map<String, Boolean> dependencies,
      Map<String, String> processEnvironment,
      Map<String, String> declaredVolumeKeys,
      List<String> actualVolumes) {
    yaml.append("  ").append(serviceKey).append(":\n")
        .append("    image: ").append(quote(image)).append("\n")
        .append("    restart: unless-stopped\n")
        .append("    labels:\n")
        .append("      " + ManagedMcpStack.OWNER_LABEL + ": '").append(ManagedMcpStack.PROJECT).append("'\n")
        .append("      " + ManagedMcpStack.SERVER_ID_LABEL + ": ").append(quote(deployment.id())).append("\n")
        .append("    networks:\n      - mcp\n");
    if (platform != null) yaml.append("    platform: ").append(quote(platform)).append("\n");
    appendList(yaml, "entrypoint", entrypoint);
    appendList(yaml, "command", command);

    if (!environment.isEmpty()) {
      yaml.append("    environment:\n");
      for (Map.Entry<String, String> entry : environment.entrySet()) {
        String variable = variableName(deployment.id(), serviceKey, entry.getKey());
        processEnvironment.put(variable, entry.getValue() == null ? "" : entry.getValue());
        yaml.append("      ").append(entry.getKey()).append(": \"${").append(variable).append(":-}\"\n");
      }
    }

    if (!volumes.isEmpty()) {
      yaml.append("    volumes:\n");
      for (VolumeSpec volume : volumes) {
        String key = volumeKey(deployment.serviceKey(), serviceKey, volume.name());
        declaredVolumeKeys.put(key, deployment.id());
        actualVolumes.add(actualVolumeName(key));
        yaml.append("      - ").append(quote(key + ":" + volume.target())).append("\n");
      }
    }

    if (!dependencies.isEmpty()) {
      yaml.append("    depends_on:\n");
      for (Map.Entry<String, Boolean> dependency : dependencies.entrySet()) {
        yaml.append("      ").append(dependency.getKey()).append(":\n")
            .append("        condition: ")
            .append(dependency.getValue() ? "service_healthy" : "service_started").append("\n");
      }
    }
    appendHealthcheck(yaml, healthcheck);
  }

  private static void appendHealthcheck(StringBuilder yaml, HealthcheckSpec healthcheck) {
    if (healthcheck == null) return;
    yaml.append("    healthcheck:\n      test:\n");
    for (String part : healthcheck.test()) yaml.append("        - ").append(quote(part)).append("\n");
    if (healthcheck.interval() != null) yaml.append("      interval: ").append(quote(healthcheck.interval())).append("\n");
    if (healthcheck.timeout() != null) yaml.append("      timeout: ").append(quote(healthcheck.timeout())).append("\n");
    if (healthcheck.retries() != null) yaml.append("      retries: ").append(healthcheck.retries()).append("\n");
    if (healthcheck.startPeriod() != null) yaml.append("      start_period: ").append(quote(healthcheck.startPeriod())).append("\n");
  }

  private static void appendList(StringBuilder yaml, String field, List<String> values) {
    if (values == null || values.isEmpty()) return;
    yaml.append("    ").append(field).append(":\n");
    for (String value : values) yaml.append("      - ").append(quote(value)).append("\n");
  }

  static String supportKey(String main, String support) {
    return main + "-" + support;
  }

  private static String volumeKey(String main, String service, String logicalName) {
    String owner = main.equals(service) ? main : service;
    return truncate(owner + "-" + logicalName, 63);
  }

  static String actualVolumeName(String volumeKey) {
    return ManagedMcpStack.volumeName(volumeKey);
  }

  private static String variableName(String serverId, String service, String key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((serverId + "\0" + service + "\0" + key).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder();
      for (int i = 0; i < 10; i++) hex.append(String.format("%02X", digest[i]));
      return "MC_MCP_" + hex;
    } catch (Exception e) {
      throw new IllegalStateException("cannot create Compose variable name", e);
    }
  }

  private static String truncate(String value, int max) {
    if (value.length() <= max) return value;
    return value.substring(0, max);
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }
}
