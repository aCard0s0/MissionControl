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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * Thin, stateless gateway over the Docker Engine API. The daemon is the
 * source of truth — nothing here is cached or persisted.
 */
@Service
public class DockerGateway {

  private final DockerClients clients;
  private final AppProperties props;

  public DockerGateway(DockerClients clients, AppProperties props) {
    this.clients = clients;
    this.props = props;
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
    try {
      client.logContainerCmd(containerId)
          .withStdOut(true)
          .withStdErr(true)
          .withTimestamps(true)
          .withTail(Math.min(Math.max(tail, 1), 500))
          .exec(new ResultCallback.Adapter<Frame>() {
            @Override
            public void onNext(Frame frame) {
              LogLineDto line = parseLogFrame(frame);
              if (line != null) {
                synchronized (lines) {
                  lines.add(line);
                }
              }
            }
          })
          .awaitCompletion(8, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return lines;
  }

  private static LogLineDto parseLogFrame(Frame frame) {
    String raw = new String(frame.getPayload(), StandardCharsets.UTF_8).stripTrailing();
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
    }

    // markers first — many daemons (nginx, java) write routine lines to stderr
    String lower = msg.toLowerCase(Locale.ROOT);
    String level;
    if (lower.contains("error") || lower.contains("fatal") || lower.contains("[emerg]")) level = "error";
    else if (lower.contains("warn")) level = "warn";
    else if (lower.contains("[notice]") || lower.contains("info") || lower.contains("debug")) level = "info";
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

    Map<String, String> labels = Map.of(
        "mc.managed", "true",
        "mc.profiles", profiles == null ? "" : String.join(",", profiles),
        "mc.dataVolume", volumeName);

    client.createVolumeCmd().withName(volumeName).exec();
    HostConfig hostConfig = HostConfig.newHostConfig()
        .withBinds(new Bind(volumeName, new Volume("/opt/data"), AccessMode.rw))
        .withRestartPolicy(RestartPolicy.unlessStoppedRestart());

    CreateContainerResponse created;
    try {
      created = client.createContainerCmd(image)
          .withName(name)
          .withLabels(labels)
          .withHostConfig(hostConfig)
          .withCmd("gateway", "run")
          .exec();
    } catch (NotFoundException missingImage) {
      pull(client, props.hermesImage(), tag);
      created = client.createContainerCmd(image)
          .withName(name)
          .withLabels(labels)
          .withHostConfig(hostConfig)
          .withCmd("gateway", "run")
          .exec();
    }
    client.startContainerCmd(created.getId()).exec();
    return created.getId();
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
    clients.forUrl(url).removeContainerCmd(containerId).withForce(true).exec();
  }
}
