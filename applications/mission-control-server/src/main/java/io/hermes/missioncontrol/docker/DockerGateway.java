package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.core.InvocationBuilder;
import com.github.dockerjava.api.async.ResultCallback;
import io.hermes.missioncontrol.AppProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    List<ContainerDto> result = new ArrayList<>();
    for (Container c : containers) {
      String name = primaryName(c);
      String image = c.getImage() == null ? "" : c.getImage();
      if (!includeAll && !filter.isEmpty()
          && !image.toLowerCase(Locale.ROOT).contains(filter)
          && !name.toLowerCase(Locale.ROOT).contains(filter)) {
        continue;
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

  private static String mapStatus(String state, String statusText) {
    String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
    if ("running".equals(s)) {
      return statusText != null && statusText.contains("(unhealthy)") ? "unhealthy" : "running";
    }
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

  // ── lifecycle ────────────────────────────────────────────────────────────

  public String deploy(String url, String hostId, String name, String version, List<String> profiles) {
    DockerClient client = clients.forUrl(url);
    String tag = version == null || version.isBlank() ? "latest" : version;
    String image = props.hermesImage() + ":" + tag;

    Map<String, String> labels = Map.of(
        "mc.managed", "true",
        "mc.profiles", profiles == null ? "" : String.join(",", profiles));

    CreateContainerResponse created;
    try {
      created = client.createContainerCmd(image).withName(name).withLabels(labels).exec();
    } catch (NotFoundException missingImage) {
      pull(client, props.hermesImage(), tag);
      created = client.createContainerCmd(image).withName(name).withLabels(labels).exec();
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
