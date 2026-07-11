package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.InvocationBuilder;
import com.github.dockerjava.api.async.ResultCallback;
import io.hermes.missioncontrol.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.hermes.missioncontrol.web.ResourceConflictException;
import org.springframework.stereotype.Service;

/**
 * Thin, stateless gateway over the Docker Engine API. The daemon is the
 * source of truth — nothing here is cached or persisted.
 */
@Service
public class DockerGateway {

  private final DockerClients clients;
  private final AppProperties props;
  private final DockerExecService dockerExec;

  public DockerGateway(DockerClients clients, AppProperties props, DockerExecService dockerExec) {
    this.clients = clients;
    this.props = props;
    this.dockerExec = dockerExec;
  }

  // ── daemon probing ───────────────────────────────────────────────────────

  public record DaemonInfo(String engine, String apiVersion, long latencyMs) {}

  public DaemonInfo ping(String url) {
    DockerClient client = clients.forUrl(url);
    long t0 = System.nanoTime();
    client.pingCmd().exec();
    long latencyMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000);
    Version version = client.versionCmd().exec();
    return new DaemonInfo("Docker " + version.getVersion(), version.getApiVersion(), latencyMs);
  }

  // ── inventory ────────────────────────────────────────────────────────────

  public List<ContainerDto> listContainers(String url, String hostId, boolean includeAll) {
    DockerClient client = clients.forUrl(url);
    List<Container> containers = client.listContainersCmd()
        .withShowAll(true)
        .withShowSize(true)
        .exec();

    String filter = props.containerFilter() == null ? "" : props.containerFilter().toLowerCase(Locale.ROOT);
    String hermesRepo = normalizeRepository(props.hermesImage());
    List<ContainerDto> result = new ArrayList<>();
    for (Container c : containers) {
      String name = primaryName(c);
      String image = c.getImage() == null ? "" : c.getImage();
      if (!includeAll) {
        String repo = normalizeRepository(splitImage(image)[0]);
        if (!hermesRepo.isEmpty()) {
          if (!hermesRepo.equals(repo)) continue;
        } else if (!filter.isEmpty()) {
          if (!image.toLowerCase(Locale.ROOT).contains(filter)
              && !name.toLowerCase(Locale.ROOT).contains(filter)) {
            continue;
          }
        }
      }
      result.add(toDto(client, c, hostId));
    }
    return result;
  }

  private ContainerDto toDto(DockerClient client, Container c, String hostId) {
    String name = primaryName(c);
    String[] imageParts = splitImage(c.getImage());
    String status = mapStatus(c.getState(), c.getStatus());

    Long startedAt = null;
    if ("running".equals(status) || "unhealthy".equals(status)) {
      try {
        String iso = client.inspectContainerCmd(c.getId()).exec().getState().getStartedAt();
        if (iso != null) startedAt = Instant.parse(iso).toEpochMilli();
      } catch (Exception ignored) {
        // inspection is best-effort; the card just shows '—' for uptime
      }
    }

    Double sizeGb = c.getSizeRootFs() != null ? c.getSizeRootFs() / 1_073_741_824.0 : null;
    Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();
    List<String> profiles = labels.containsKey("mc.profiles") && !labels.get("mc.profiles").isBlank()
        ? List.of(labels.get("mc.profiles").split(","))
        : List.of();

    return new ContainerDto(
        c.getId(), c.getId().substring(0, Math.min(7, c.getId().length())), name, hostId,
        status, imageParts[0], imageParts[1], startedAt, sizeGb, profiles);
  }

  private static String primaryName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return "?";
    return names[0].startsWith("/") ? names[0].substring(1) : names[0];
  }

  private static String[] splitImage(String image) {
    if (image == null) return new String[]{"?", "?"};
    int idx = image.lastIndexOf(':');
    // a ':' inside a registry host:port segment is not a tag separator
    if (idx > 0 && image.indexOf('/', idx) == -1) {
      return new String[]{image.substring(0, idx), image.substring(idx + 1)};
    }
    return new String[]{image, "latest"};
  }

  private static String normalizeRepository(String repository) {
    if (repository == null) return "";
    String repo = repository;
    int idx = repo.lastIndexOf(':');
    if (idx > 0 && repo.indexOf('/', idx) == -1) {
      repo = repo.substring(0, idx);
    }
    String normalized = repo.toLowerCase(Locale.ROOT);
    if (normalized.startsWith("docker.io/")) {
      return normalized.substring("docker.io/".length());
    }
    if (normalized.startsWith("registry-1.docker.io/")) {
      return normalized.substring("registry-1.docker.io/".length());
    }
    if (normalized.startsWith("index.docker.io/")) {
      return normalized.substring("index.docker.io/".length());
    }
    return normalized;
  }

  private static String mapStatus(String state, String statusText) {
    String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
    if ("running".equals(s)) {
      return statusText != null && statusText.contains("(unhealthy)") ? "unhealthy" : "running";
    }
    if ("restarting".equals(s)) return "unhealthy";
    if ("exited".equals(s) || "created".equals(s) || "paused".equals(s) || "dead".equals(s)) return "stopped";
    return "unknown";
  }

  // ── stats / logs ─────────────────────────────────────────────────────────

  public StatsDto stats(String url, String containerId) {
    DockerClient client = clients.forUrl(url);
    try (InvocationBuilder.AsyncResultCallback<Statistics> callback = new InvocationBuilder.AsyncResultCallback<>()) {
      client.statsCmd(containerId).withNoStream(true).exec(callback);
      Statistics stats = callback.awaitResult();
      return toStats(stats);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("stats failed: " + e.getMessage(), e);
    }
  }

  private static StatsDto toStats(Statistics stats) {
    double cpu = 0;
    var cpuStats = stats.getCpuStats();
    var preCpu = stats.getPreCpuStats();
    if (cpuStats != null && preCpu != null
        && cpuStats.getCpuUsage() != null && preCpu.getCpuUsage() != null
        && cpuStats.getSystemCpuUsage() != null && preCpu.getSystemCpuUsage() != null) {
      long cpuDelta = orZero(cpuStats.getCpuUsage().getTotalUsage()) - orZero(preCpu.getCpuUsage().getTotalUsage());
      long sysDelta = cpuStats.getSystemCpuUsage() - preCpu.getSystemCpuUsage();
      long cpus = cpuStats.getOnlineCpus() != null ? cpuStats.getOnlineCpus() : 1;
      if (sysDelta > 0 && cpuDelta >= 0) cpu = (double) cpuDelta / sysDelta * cpus * 100.0;
    }

    double ramMb = 0;
    double ramTotalMb = 0;
    if (stats.getMemoryStats() != null) {
      ramMb = orZero(stats.getMemoryStats().getUsage()) / 1_048_576.0;
      ramTotalMb = orZero(stats.getMemoryStats().getLimit()) / 1_048_576.0;
    }

    long rx = 0;
    long tx = 0;
    Map<String, StatisticNetworksConfig> networks = stats.getNetworks();
    if (networks != null) {
      for (StatisticNetworksConfig net : networks.values()) {
        rx += orZero(net.getRxBytes());
        tx += orZero(net.getTxBytes());
      }
    }

    return new StatsDto(cpu, ramMb, ramTotalMb, rx, tx, System.currentTimeMillis());
  }

  private static long orZero(Long value) {
    return value == null ? 0 : value;
  }

  public List<LogLineDto> logs(String url, String containerId, int tail) {
    DockerClient client = clients.forUrl(url);
    List<LogLineDto> lines = new ArrayList<>();
    try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Frame frame) {
        List<LogLineDto> parsed = parseLogFrame(frame);
        if (!parsed.isEmpty()) {
          synchronized (lines) {
            lines.addAll(parsed);
          }
        }
      }
    }) {
      client.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withTimestamps(true)
          .withTail(Math.min(Math.max(tail, 1), 500))
          .exec(callback);
      callback.awaitCompletion(8, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw e instanceof RuntimeException runtime ? runtime
          : new RuntimeException("logs failed: " + e.getMessage(), e);
    }
    return lines;
  }

  /**
   * Attaches an existing container to a named Docker network. The operation is
   * idempotent, including when another request wins the connect race between
   * our inspection and the Engine call.
   */
  public void connectNetwork(String url, String containerId, String networkName) {
    if (networkName == null || networkName.isBlank()) {
      throw new IllegalArgumentException("missing network name");
    }
    DockerClient client = clients.forUrl(url);
    if (containerUsesNetwork(client, containerId, networkName)) return;
    String networkId = client.listNetworksCmd().withNameFilter(networkName).exec().stream()
        .filter(network -> networkName.equals(network.getName()))
        .map(com.github.dockerjava.api.model.Network::getId)
        .findFirst()
        .orElseThrow(() -> new NotFoundException("network not found: " + networkName));
    try {
      client.connectToNetworkCmd()
          .withContainerId(containerId)
          .withNetworkId(networkId)
          .exec();
    } catch (RuntimeException race) {
      if (!containerUsesNetwork(client, containerId, networkName)) throw race;
    }
  }

  private static boolean containerUsesNetwork(
      DockerClient client, String containerId, String networkName) {
    var inspected = client.inspectContainerCmd(containerId).exec();
    var settings = inspected.getNetworkSettings();
    return settings != null && settings.getNetworks() != null
        && settings.getNetworks().containsKey(networkName);
  }

  static List<LogLineDto> parseLogFrame(Frame frame) {
    String payload = new String(frame.getPayload(), StandardCharsets.UTF_8);
    List<LogLineDto> parsed = new ArrayList<>();
    for (String raw : payload.split("\\R", -1)) {
      LogLineDto line = parseLogLine(frame, raw.stripTrailing());
      if (line != null) parsed.add(line);
    }
    return parsed;
  }

  private static LogLineDto parseLogLine(Frame frame, String raw) {
    if (raw.isBlank()) return null;

    long ts = System.currentTimeMillis();
    String msg = raw;
    int space = raw.indexOf(' ');
    if (space > 0) {
      try {
        ts = Instant.parse(raw.substring(0, space)).toEpochMilli();
        msg = raw.substring(space + 1);
      } catch (Exception ignored) {
        // line without a leading docker timestamp — keep it whole
      }
    } else {
      // Docker prefixes even an empty application record with its timestamp.
      // Treat that as an empty line instead of a new message stamped "now".
      try {
        Instant.parse(raw);
        return null;
      } catch (Exception ignored) { }
    }

    if (msg.isBlank()) return null;

    // Explicit severity wins over keywords inside the prose. In particular,
    // "WARNING ... connection failed ... error" is still a warning.
    String lower = msg.stripLeading().toLowerCase(Locale.ROOT);
    String level;
    if (lower.startsWith("warning") || lower.startsWith("warn") || lower.startsWith("[warn")) level = "warn";
    else if (lower.startsWith("debug") || lower.startsWith("[debug")) level = "debug";
    else if (lower.startsWith("error") || lower.startsWith("fatal") || lower.startsWith("[emerg]")
        || lower.startsWith("traceback") || lower.contains("permissionerror:")
        || lower.contains("exception:") || lower.contains("fatal error")) level = "error";
    else if (lower.startsWith("info") || lower.startsWith("[notice]") || lower.startsWith("[info")
        || lower.contains(": info:")) level = "info";
    else level = frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR ? "warn" : "info";
    return new LogLineDto(ts, level, "container", msg);
  }

  // ── images ──────────────────────────────────────────────────────────────

  public ImageTagsDto imageTags(String url) {
    DockerClient client = clients.forUrl(url);
    String repository = props.hermesImage() == null ? "" : props.hermesImage();
    String targetRepo = normalizeRepository(repository);
    if (targetRepo.isBlank()) {
      return new ImageTagsDto(repository, List.of());
    }
    Set<String> tags = new HashSet<>();
    List<Image> images = client.listImagesCmd().withShowAll(true).exec();
    for (Image image : images) {
      String[] repoTags = image.getRepoTags();
      if (repoTags == null) continue;
      for (String repoTag : repoTags) {
        if (repoTag == null || repoTag.contains("<none>")) continue;
        String[] parts = splitImage(repoTag);
        String repo = normalizeRepository(parts[0]);
        if (!targetRepo.equals(repo)) continue;
        String tag = parts[1];
        if (tag != null && !tag.isBlank()) tags.add(tag);
      }
    }
    List<String> sorted = new ArrayList<>(tags);
    sorted.sort(DockerGateway::compareTags);
    return new ImageTagsDto(repository, sorted);
  }

  private static int compareTags(String left, String right) {
    if ("latest".equals(left)) return "latest".equals(right) ? 0 : -1;
    if ("latest".equals(right)) return 1;
    int[] leftVer = parseSemver(left);
    int[] rightVer = parseSemver(right);
    if (leftVer != null && rightVer != null) {
      for (int i = 0; i < 3; i++) {
        if (leftVer[i] != rightVer[i]) {
          return Integer.compare(rightVer[i], leftVer[i]);
        }
      }
      return 0;
    }
    if (leftVer != null) return -1;
    if (rightVer != null) return 1;
    return right.compareTo(left);
  }

  private static int[] parseSemver(String tag) {
    if (tag == null) return null;
    String trimmed = tag.startsWith("v") ? tag.substring(1) : tag;
    if (!trimmed.matches("\\d+(\\.\\d+){0,2}")) return null;
    String[] parts = trimmed.split("\\.");
    int[] result = new int[]{0, 0, 0};
    for (int i = 0; i < parts.length && i < 3; i++) {
      result[i] = Integer.parseInt(parts[i]);
    }
    return result;
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  public String deploy(String url, String hostId, String name, String version, List<String> profiles) {
    DockerClient client = clients.forUrl(url);
    String tag = version == null || version.isBlank() ? "latest" : version;
    String image = props.hermesImage() + ":" + tag;
    String volumeName = "mc-hermes-" + name;
    List<String> seedProfiles = normalizeProfiles(profiles);

    try {
      client.inspectVolumeCmd(volumeName).exec();
      throw new ResourceConflictException(
          "managed data volume already exists: " + volumeName + "; recover or remove it before redeploying");
    } catch (NotFoundException expected) {
      // no legacy data to attach accidentally
    }

    Map<String, String> labels = Map.of(
        "mc.managed", "true",
        "mc.profiles", String.join(",", seedProfiles),
        "mc.dataVolume", volumeName);

    String containerId = null;
    boolean volumeCreated = false;
    try {
      client.createVolumeCmd().withName(volumeName).exec();
      volumeCreated = true;
      HostConfig dataHostConfig = HostConfig.newHostConfig()
          .withBinds(new Bind(volumeName, new Volume("/opt/data"), AccessMode.rw));
      // One-shot containers run the image's normal init hooks before their main
      // command. This seeds the default profile and creates named profiles while
      // the long-running gateway is still stopped, avoiding restart/exec races.
      runOneShot(client, image, dataHostConfig, List.of("true"), "initialize Hermes data volume");
      for (String profile : seedProfiles) {
        runOneShot(client, image, dataHostConfig,
            List.of("profile", "create", profile, "--no-alias"),
            "create seed profile " + profile);
      }

      HostConfig hostConfig = HostConfig.newHostConfig()
          .withBinds(new Bind(volumeName, new Volume("/opt/data"), AccessMode.rw))
          .withRestartPolicy(RestartPolicy.unlessStoppedRestart());

      CreateContainerResponse created;
      try {
        created = createContainer(client, image, name, labels, hostConfig, List.of("gateway", "run"));
      } catch (NotFoundException missingImage) {
        pull(client, props.hermesImage(), tag);
        created = createContainer(client, image, name, labels, hostConfig, List.of("gateway", "run"));
      }
      containerId = created.getId();
      client.startContainerCmd(containerId).exec();
      validateDeployment(url, client, containerId, seedProfiles);
      return containerId;
    } catch (RuntimeException failure) {
      rollbackDeployment(client, containerId, volumeCreated ? volumeName : null, failure);
      throw failure;
    }
  }

  private void validateDeployment(
      String url, DockerClient client, String containerId, List<String> seedProfiles) {
    var state = client.inspectContainerCmd(containerId).exec().getState();
    if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
      throw new RuntimeException("Hermes container exited before readiness checks completed");
    }

    List<String> profiles = new ArrayList<>();
    profiles.add("default");
    profiles.addAll(seedProfiles);
    String script = """
        set -eu
        for profile in "$@"; do
          if [ "$profile" = default ]; then
            dir=/opt/data
            test -r "$dir/config.yaml" || { echo "profile config is unreadable: $profile" >&2; exit 1; }
          else
            dir="/opt/data/profiles/$profile"
            test -d "$dir" || { echo "profile directory is missing: $profile" >&2; exit 1; }
            if [ -e "$dir/config.yaml" ]; then
              test -r "$dir/config.yaml" || { echo "profile config is unreadable: $profile" >&2; exit 1; }
            fi
          fi
          if [ -e "$dir/.env" ]; then
            test -r "$dir/.env" || { echo "profile environment is unreadable: $profile" >&2; exit 1; }
          fi
        done
        hermes profile list >/dev/null 2>&1 || { echo "hermes profile list failed" >&2; exit 1; }
        tries=0
        while true; do
          if detail="$(hermes gateway status 2>&1)" && printf '%s' "$detail" | grep -q 'Gateway is running'; then
            break
          fi
          tries=$((tries + 1))
          if [ "$tries" -ge 30 ]; then
            echo "default gateway not ready: $(printf '%s' "$detail" | tail -n 1)" >&2
            exit 1
          fi
          sleep 1
        done
        """;
    List<String> command = new ArrayList<>(List.of("sh", "-c", script, "_"));
    command.addAll(profiles);
    dockerExec.runAsUser(
        url, containerId, "hermes", command, "Hermes deployment readiness",
        true, false, Duration.ofSeconds(45));

    state = client.inspectContainerCmd(containerId).exec().getState();
    if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
      throw new RuntimeException("Hermes container stopped during readiness checks");
    }
  }

  private static CreateContainerResponse createContainer(
      DockerClient client, String image, String name, Map<String, String> labels,
      HostConfig hostConfig, List<String> command) {
    var create = client.createContainerCmd(image)
        .withName(name)
        .withLabels(labels)
        .withHostConfig(hostConfig);
    return create.withCmd(command).exec();
  }

  static List<String> normalizeProfiles(List<String> profiles) {
    if (profiles == null || profiles.isEmpty()) return List.of();
    Set<String> unique = new LinkedHashSet<>();
    for (String profile : profiles) {
      if (profile == null) continue;
      String normalized = profile.trim();
      if (!normalized.isEmpty() && !"default".equals(normalized)) unique.add(normalized);
    }
    return List.copyOf(unique);
  }

  void runOneShot(
      DockerClient client, String image, HostConfig hostConfig, List<String> command, String operation) {
    String helperId = null;
    RuntimeException failure = null;
    try {
      CreateContainerResponse helper;
      try {
        helper = client.createContainerCmd(image)
            .withLabels(Map.of("mc.bootstrap", "true"))
            .withHostConfig(hostConfig)
            .withCmd(command)
            .exec();
      } catch (NotFoundException missingImage) {
        String[] parts = splitImage(image);
        pull(client, parts[0], parts[1]);
        helper = client.createContainerCmd(image)
            .withLabels(Map.of("mc.bootstrap", "true"))
            .withHostConfig(hostConfig)
            .withCmd(command)
            .exec();
      }
      helperId = helper.getId();
      client.startContainerCmd(helperId).exec();
      var callback = client.waitContainerCmd(helperId).start();
      try {
        Integer exitCode = callback.awaitStatusCode(90, TimeUnit.SECONDS);
        if (exitCode == null) throw new RuntimeException(operation + " timed out");
        if (exitCode != 0) throw new RuntimeException(operation + " failed with exit code " + exitCode);
      } finally {
        try {
          callback.close();
        } catch (Exception ignored) { }
      }
    } catch (RuntimeException e) {
      failure = e;
      throw e;
    } finally {
      if (helperId != null) {
        try {
          client.removeContainerCmd(helperId).withForce(true).exec();
        } catch (RuntimeException cleanup) {
          if (failure != null) failure.addSuppressed(cleanup);
          else throw cleanup;
        }
      }
    }
  }

  private void rollbackDeployment(
      DockerClient client, String containerId, String volumeName, RuntimeException failure) {
    if (containerId != null) {
      try {
        client.removeContainerCmd(containerId).withForce(true).exec();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
    if (volumeName != null) {
      try {
        client.removeVolumeCmd(volumeName).exec();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  private static void pull(DockerClient client, String repository, String tag) {
    try (var callback = client.pullImageCmd(repository).withTag(tag)
        .exec(new com.github.dockerjava.api.command.PullImageResultCallback())) {
      if (!callback.awaitCompletion(180, TimeUnit.SECONDS)) {
        throw new RuntimeException("image pull timed out: " + repository + ":" + tag);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("image pull interrupted", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("image pull failed: " + e.getMessage(), e);
    }
  }

  public void start(String url, String containerId) {
    clients.forUrl(url).startContainerCmd(containerId).exec();
  }

  public void stop(String url, String containerId) {
    clients.forUrl(url).stopContainerCmd(containerId).withTimeout(10).exec();
  }

  public void remove(String url, String containerId) {
    DockerClient client = clients.forUrl(url);
    var inspected = client.inspectContainerCmd(containerId).exec();
    Map<String, String> labels = inspected.getConfig() == null || inspected.getConfig().getLabels() == null
        ? Map.of() : inspected.getConfig().getLabels();
    String volumeName = "true".equals(labels.get("mc.managed")) ? labels.get("mc.dataVolume") : null;
    client.removeContainerCmd(containerId).withForce(true).exec();
    if (volumeName == null || !volumeName.startsWith("mc-hermes-")) return;
    try {
      client.removeVolumeCmd(volumeName).exec();
    } catch (NotFoundException ignored) {
      // idempotent permanent removal
    } catch (RuntimeException e) {
      throw new RuntimeException(
          "container removed but managed data volume could not be removed: " + volumeName, e);
    }
  }
}
