package io.hermes.missioncontrol.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Renders the deliberately narrow managed-server model into non-secret Compose YAML.
 *
 * <p>Every name this emits that something else has to recognise — the project, the network, the
 * ownership labels — comes from {@link ManagedMcpStack}, because the reader is always somewhere
 * else: {@link ComposeStackManager} inspects the labels back, {@link McpHealthProbe} and the
 * {@code agents} package join the network.
 *
 * <p>The document is built as nested maps and handed to SnakeYAML, which Spring Boot already
 * ships and this codebase already parses with. It used to be appended to a {@link StringBuilder}
 * a line at a time behind a hand-rolled {@code quote()} — a second, worse YAML implementation
 * whose escaping rule was one {@code replace} call, and which could not be checked except by
 * matching the whitespace it happened to emit.
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
    Map<String, Object> services = new LinkedHashMap<>();
    Map<String, String> processEnvironment = new LinkedHashMap<>();
    Map<String, List<String>> serviceNames = new LinkedHashMap<>();
    Map<String, List<String>> volumeNames = new LinkedHashMap<>();
    Map<String, String> declaredVolumeKeys = new LinkedHashMap<>();

    for (Deployment deployment : deployments) {
      StoredConfig config = deployment.config();
      List<String> names = new ArrayList<>();
      List<String> actualVolumes = new ArrayList<>();
      RenderState state = new RenderState(processEnvironment, declaredVolumeKeys, actualVolumes);

      for (StoredSupportService support : config.supportServices()) {
        String supportKey = supportKey(deployment.serviceKey(), support.name());
        names.add(supportKey);
        services.put(supportKey, service(deployment, supportKey,
            new ServiceSpec(support.image(), support.platform(), support.entrypoint(),
                support.command(),
                deployment.supportEnvironment().getOrDefault(support.name(), Map.of()),
                support.volumes(), support.healthcheck()),
            Map.of(), state));
      }

      names.addFirst(deployment.serviceKey());
      Map<String, Boolean> dependencies = new LinkedHashMap<>();
      for (StoredSupportService support : config.supportServices()) {
        boolean healthy = support.healthcheck() != null
            && !"NONE".equals(support.healthcheck().test().getFirst());
        dependencies.put(supportKey(deployment.serviceKey(), support.name()), healthy);
      }
      Map<String, Object> main = service(deployment, deployment.serviceKey(),
          new ServiceSpec(config.image(), config.platform(), config.entrypoint(), config.command(),
              deployment.mainEnvironment(), config.volumes(), config.healthcheck()),
          dependencies, state);
      if (config.internalPort() != null) {
        main.put("expose", List.of(String.valueOf(config.internalPort())));
      }
      if (config.publishedPort() != null) {
        main.put("ports", List.of(config.publishedPort() + ":" + config.internalPort()));
      }
      services.put(deployment.serviceKey(), main);
      serviceNames.put(deployment.id(), List.copyOf(names));
      volumeNames.put(deployment.id(), List.copyOf(new LinkedHashSet<>(actualVolumes)));
    }

    Map<String, Object> document = new LinkedHashMap<>();
    document.put("services", services);
    document.put("networks", Map.of("mcp", ordered(
        "name", ManagedMcpStack.NETWORK,
        "driver", "bridge",
        "labels", Map.of(ManagedMcpStack.OWNER_LABEL, ManagedMcpStack.PROJECT))));
    if (!declaredVolumeKeys.isEmpty()) {
      Map<String, Object> volumes = new LinkedHashMap<>();
      declaredVolumeKeys.forEach((volumeKey, serverId) -> volumes.put(volumeKey, ordered(
          "name", actualVolumeName(volumeKey),
          "labels", ordered(
              ManagedMcpStack.OWNER_LABEL, ManagedMcpStack.PROJECT,
              ManagedMcpStack.SERVER_ID_LABEL, serverId))));
      document.put("volumes", volumes);
    }
    return new Rendered(dump(document), Map.copyOf(processEnvironment),
        Map.copyOf(serviceNames), Map.copyOf(volumeNames));
  }

  /** The seven fields a main service and a support service describe identically. */
  private record ServiceSpec(
      String image, String platform, List<String> entrypoint, List<String> command,
      Map<String, String> environment, List<VolumeSpec> volumes, HealthcheckSpec healthcheck) {}

  /** What one render accumulates across every service it visits. */
  private record RenderState(
      Map<String, String> processEnvironment,
      Map<String, String> declaredVolumeKeys,
      List<String> actualVolumes) {}

  private static Map<String, Object> service(Deployment deployment, String serviceKey,
      ServiceSpec spec, Map<String, Boolean> dependencies, RenderState state) {
    Map<String, Object> service = ordered(
        "image", spec.image(),
        "restart", "unless-stopped",
        "labels", ordered(
            ManagedMcpStack.OWNER_LABEL, ManagedMcpStack.PROJECT,
            ManagedMcpStack.SERVER_ID_LABEL, deployment.id()),
        "networks", List.of("mcp"));
    if (spec.platform() != null) service.put("platform", spec.platform());
    putIfAny(service, "entrypoint", spec.entrypoint());
    putIfAny(service, "command", spec.command());

    if (!spec.environment().isEmpty()) {
      Map<String, Object> env = new LinkedHashMap<>();
      spec.environment().forEach((key, value) -> {
        String variable = variableName(deployment.id(), serviceKey, key);
        state.processEnvironment().put(variable, value == null ? "" : value);
        env.put(key, "${" + variable + ":-}");
      });
      service.put("environment", env);
    }

    if (!spec.volumes().isEmpty()) {
      List<String> mounts = new ArrayList<>();
      for (VolumeSpec volume : spec.volumes()) {
        String key = volumeKey(deployment.serviceKey(), serviceKey, volume.name());
        state.declaredVolumeKeys().put(key, deployment.id());
        state.actualVolumes().add(actualVolumeName(key));
        mounts.add(key + ":" + volume.target());
      }
      service.put("volumes", mounts);
    }

    if (!dependencies.isEmpty()) {
      Map<String, Object> depends = new LinkedHashMap<>();
      dependencies.forEach((name, healthy) -> depends.put(name,
          Map.of("condition", healthy ? "service_healthy" : "service_started")));
      service.put("depends_on", depends);
    }
    if (spec.healthcheck() != null) service.put("healthcheck", healthcheck(spec.healthcheck()));
    return service;
  }

  private static Map<String, Object> healthcheck(HealthcheckSpec spec) {
    Map<String, Object> check = ordered("test", List.copyOf(spec.test()));
    if (spec.interval() != null) check.put("interval", spec.interval());
    if (spec.timeout() != null) check.put("timeout", spec.timeout());
    if (spec.retries() != null) check.put("retries", spec.retries());
    if (spec.startPeriod() != null) check.put("start_period", spec.startPeriod());
    return check;
  }

  /**
   * Block style, two-space indent, sequence dashes indented under their key.
   *
   * <p>Compose reads either layout; the indented one is what a human diffing a generated stack
   * against a hand-written one expects to see.
   */
  private static String dump(Map<String, Object> document) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setIndicatorIndent(2);
    options.setIndentWithIndicator(true);
    options.setSplitLines(false);
    return new Yaml(options).dump(document);
  }

  /** A {@link LinkedHashMap} literal — {@code Map.of} does not keep the order it was written in. */
  private static Map<String, Object> ordered(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  private static void putIfAny(Map<String, Object> service, String field, List<String> values) {
    if (values != null && !values.isEmpty()) service.put(field, List.copyOf(values));
  }

  static String supportKey(String main, String support) {
    return main + "-" + support;
  }

  private static String volumeKey(String main, String service, String logicalName) {
    String owner = main.equals(service) ? main : service;
    String key = owner + "-" + logicalName;
    // Compose/Docker cap a volume name at 63 characters
    return key.substring(0, Math.min(63, key.length()));
  }

  static String actualVolumeName(String volumeKey) {
    return ManagedMcpStack.volumeName(volumeKey);
  }

  private static String variableName(String serverId, String service, String key) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((serverId + "\0" + service + "\0" + key).getBytes(StandardCharsets.UTF_8));
      return "MC_MCP_" + HexFormat.of().withUpperCase().formatHex(digest, 0, 10);
    } catch (Exception e) {
      throw new IllegalStateException("cannot create Compose variable name", e);
    }
  }
}
