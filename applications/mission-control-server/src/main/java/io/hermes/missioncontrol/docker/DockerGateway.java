package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.Version;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.InvocationBuilder;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin, stateless gateway over the Docker Engine API. The daemon is the
 * source of truth — nothing here is cached or persisted.
 */
@Service
public class DockerGateway {

  private static final Logger log = LoggerFactory.getLogger(DockerGateway.class);

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
      Map<String, String> containerLabels = c.getLabels() == null ? Map.of() : c.getLabels();
      if (!includeAll) {
        if (isUpgradeLeftover(name)) {
          // a daemon crash mid-upgrade can strand the parked original; keep it out
          // of the fleet view but reachable through ?all=true
          log.warn("ignoring container left parked by an interrupted upgrade: {}", name);
          continue;
        }
        if ("true".equals(containerLabels.get("mc.bootstrap"))) {
          // a short-lived seeding helper, or one stranded by a failed deploy — never an Agent
          continue;
        }
        // A container we deployed is ours whatever its image reference reads as, so it is
        // never matched on that reference. The Engine substitutes the raw image ID once a
        // reference stops resolving — moving the `latest` tag during one Agent's upgrade
        // does exactly that to every other Agent on that tag — and an ID parses as
        // repository "sha256", matching nothing and dropping a live Agent out of the fleet.
        if (!"true".equals(containerLabels.get("mc.managed"))) {
          if (isImageIdReference(image)) {
            log.warn("container {} reports an image id rather than a reference and is not "
                + "Mission Control-managed, so it is not shown in the fleet", name);
            continue;
          }
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
      }
      result.add(toDto(client, c, hostId));
    }
    return result;
  }

  private ContainerDto toDto(DockerClient client, Container c, String hostId) {
    String name = primaryName(c);
    String[] imageParts = splitImage(c.getImage());
    if (isImageIdReference(c.getImage())) {
      // "sha256:e5b3…" would otherwise render as repository "sha256" with the hex as the
      // version. Report the repository the container is known to run and leave the tag
      // blank — the reference it was created from is genuinely no longer recoverable here.
      imageParts = new String[]{hermesRepository(), ""};
    }
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
    return ImageRef.splitImage(image);
  }

  private static String normalizeRepository(String repository) {
    return ImageRef.normalizeRepository(repository);
  }

  /**
   * True when the Engine reported a bare image ID instead of a reference. It does that once
   * the stored reference no longer resolves to the container's image — notably after another
   * container's upgrade moves a floating tag. Such a value is not a repository, so matching
   * it against one is meaningless.
   */
  static boolean isImageIdReference(String image) {
    if (image == null || image.isBlank()) return false;
    String value = image.startsWith("sha256:") ? image.substring("sha256:".length()) : image;
    return value.length() >= 32 && value.chars()
        .allMatch(ch -> (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'));
  }

  /**
   * Repository half of MC_HERMES_IMAGE. The property is documented as a
   * repository, but the filtering paths tolerate a tag on it — so the paths that
   * build a reference must strip one rather than emit 'repo:tag:tag'.
   */
  private String hermesRepository() {
    return ImageRef.splitImage(props.hermesImage())[0];
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
      if (stats == null) {
        // the stream completed without ever delivering a sample — a truncated response, or
        // a daemon restarting mid-request. awaitResult reports that as null rather than an
        // error, and mapping null would surface a defect-shaped 500 for a dependency outage.
        throw new UpstreamUnavailableException("stats returned no sample for " + containerId);
      }
      return toStats(stats);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new UpstreamUnavailableException("stats failed: " + e.getMessage(), e);
    }
  }

  /** Package-private for the same reason as {@link #parseLogFrame}: it is pure mapping worth pinning. */
  static StatsDto toStats(Statistics stats) {
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
      ramMb = usageWithoutCache(stats.getMemoryStats()) / 1_048_576.0;
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

  /**
   * Memory usage with the reclaimable page cache subtracted, the way {@code docker stats}
   * itself reports it.
   *
   * <p>Raw {@code memory_stats.usage} counts every page the kernel cached on the
   * container's behalf and will drop under pressure. An Agent that has read a few GB of
   * skills or model files otherwise shows as pinned at its limit and about to OOM while
   * the daemon reports it comfortably idle.
   *
   * <p>cgroup v1 reports {@code total_inactive_file}, cgroup v2 {@code inactive_file};
   * a value larger than usage is nonsense from a partial sample, so it is ignored.
   */
  static long usageWithoutCache(com.github.dockerjava.api.model.MemoryStatsConfig memory) {
    long usage = orZero(memory.getUsage());
    var detail = memory.getStats();
    if (detail == null) return usage;
    Long cache = detail.getTotalInactiveFile() != null
        ? detail.getTotalInactiveFile() : detail.getInactiveFile();
    if (cache == null || cache < 0 || cache > usage) return usage;
    return usage - cache;
  }

  public List<LogLineDto> logs(String url, String containerId, int tail) {
    DockerClient client = clients.forUrl(url);
    List<LogLineDto> lines = new ArrayList<>();
    boolean complete = false;
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
      complete = callback.awaitCompletion(8, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      throw e instanceof RuntimeException runtime ? runtime
          : new RuntimeException("logs failed: " + e.getMessage(), e);
    }
    if (!complete) {
      // close() does not join the reader thread, so it may still append after the wait
      // expires. Returning the live list would hand a mutating ArrayList to Jackson.
      log.warn("log tail for {} was cut short after 8s — returning a partial read", containerId);
    }
    synchronized (lines) {
      return List.copyOf(lines);
    }
  }

  /**
   * Attaches an existing container to a named Docker network. The operation is
   * idempotent, including when another request wins the connect race between
   * our inspection and the Engine call.
   */
  public void connectNetwork(String url, String containerId, String networkName) {
    connectNetwork(url, containerId, networkName, List.of());
  }

  /** As above, preserving the network aliases a container was reachable under. */
  public void connectNetwork(
      String url, String containerId, String networkName, List<String> aliases) {
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
      var connect = client.connectToNetworkCmd()
          .withContainerId(containerId)
          .withNetworkId(networkId);
      if (aliases != null && !aliases.isEmpty()) {
        connect.withContainerNetwork(new ContainerNetwork().withAliases(aliases));
      }
      connect.exec();
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
    // "WARNING ... connection failed ... error" is still a warning, and
    // "INFO ... retrying after exception: ..." is still info — so every explicit
    // prefix is tested before any substring heuristic runs.
    String lower = msg.stripLeading().toLowerCase(Locale.ROOT);
    String level = explicitLevel(lower);
    if (level == null) level = keywordLevel(lower);
    if (level == null) {
      level = frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR ? "warn" : "info";
    }
    return new LogLineDto(ts, level, "container", msg);
  }

  /** Severity the line states about itself, or null when it carries no level prefix. */
  private static String explicitLevel(String lower) {
    if (lower.startsWith("warning") || lower.startsWith("warn") || lower.startsWith("[warn")) return "warn";
    if (lower.startsWith("debug") || lower.startsWith("[debug")) return "debug";
    if (lower.startsWith("error") || lower.startsWith("fatal") || lower.startsWith("[emerg]")
        || lower.startsWith("traceback")) {
      return "error";
    }
    if (lower.startsWith("info") || lower.startsWith("[notice]") || lower.startsWith("[info")) return "info";
    return null;
  }

  /** Severity inferred from the prose, for lines that never named one. */
  private static String keywordLevel(String lower) {
    if (lower.contains("permissionerror:") || lower.contains("exception:")
        || lower.contains("fatal error")) {
      return "error";
    }
    if (lower.contains(": info:")) return "info";
    return null;
  }

  // ── images ──────────────────────────────────────────────────────────────

  /** Tags of the configured Hermes image already present in this host's image store. */
  public Set<String> localImageTags(String url) {
    String targetRepo = normalizeRepository(props.hermesImage());
    if (targetRepo.isBlank()) return Set.of();
    DockerClient client = clients.forUrl(url);
    Set<String> tags = new HashSet<>();
    // no withShowAll: that only adds intermediate and dangling layers, which carry a null or
    // <none> repo tag and are discarded immediately below
    List<Image> images = client.listImagesCmd().exec();
    for (Image image : images) {
      String[] repoTags = image.getRepoTags();
      if (repoTags == null) continue;
      for (String repoTag : repoTags) {
        if (repoTag == null || repoTag.contains("<none>")) continue;
        String[] parts = splitImage(repoTag);
        if (!targetRepo.equals(normalizeRepository(parts[0]))) continue;
        String tag = parts[1];
        if (tag != null && !tag.isBlank()) tags.add(tag);
      }
    }
    return tags;
  }

  /**
   * The repository half of the configured Hermes image, as the catalog reports it.
   *
   * <p>Tag-stripped like every sibling path: the frontend gates its update badges on this
   * value matching {@code ContainerDto.image}, which is already a bare repository. Echoing
   * a tagged MC_HERMES_IMAGE verbatim compares unequal and silently retires the upgrade
   * affordance for the whole fleet.
   */
  public String hermesImageRepository() {
    return props.hermesImage() == null ? "" : hermesRepository();
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  public String deploy(String url, String hostId, String name, String version, List<String> profiles) {
    DockerClient client = clients.forUrl(url);
    String tag = version == null || version.isBlank() ? "latest" : version;
    String image = hermesRepository() + ":" + tag;
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
      runOneShot(url, client, image, dataHostConfig, List.of("true"), "initialize Hermes data volume");
      for (String profile : seedProfiles) {
        runOneShot(url, client, image, dataHostConfig,
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
        pull(clients.streamingForUrl(url), hermesRepository(), tag);
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
      throw new UpstreamUnavailableException("Hermes container exited before readiness checks completed");
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
    try {
      dockerExec.runAsUser(
          url, containerId, "hermes", command, "Hermes deployment readiness",
          true, false, Duration.ofSeconds(45));
    } catch (UpstreamUnavailableException already) {
      throw already;
    } catch (RuntimeException notReady) {
      // A non-zero readiness exit is a bare RuntimeException, which the advice answers as
      // 500 with a stack trace — for the same operational outcome the checks two lines
      // above and below report as 503. A gateway that is slow to come up is not a defect.
      throw new UpstreamUnavailableException(
          "Hermes readiness checks failed: " + notReady.getMessage(), notReady);
    }

    state = client.inspectContainerCmd(containerId).exec().getState();
    if (state == null || !Boolean.TRUE.equals(state.getRunning())) {
      throw new UpstreamUnavailableException("Hermes container stopped during readiness checks");
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

  // ── image updates ────────────────────────────────────────────────────────

  /**
   * A managed container's identity, captured before it is replaced. Copied from
   * the daemon rather than rebuilt from configuration: an Agent may have been
   * attached to the managed MCP network after it was deployed, and the
   * {@code mc.profiles} label records what was seeded, not what exists now.
   */
  public record ManagedContainerSpec(
      String id,
      String name,
      String tag,
      String imageId,
      Map<String, String> labels,
      List<Bind> binds,
      RestartPolicy restartPolicy,
      List<String> cmd,
      List<String> entrypoint,
      List<String> env,
      String user,
      String workingDir,
      String primaryNetwork,
      Map<String, List<String>> extraNetworks,
      boolean wasRunning,
      String dataVolume) {
  }

  public record UpgradeResult(
      String oldContainerId, String newContainerId, String fromTag, String toTag, boolean running) {
  }

  /** Suffix given to a container parked during an upgrade, until the new one is verified. */
  static final String UPGRADE_SUFFIX = "-mc-upgrade-";

  /** Docker's own networks — a new container joins these without being connected. */
  private static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none");

  /**
   * Inspects a container and refuses anything this dashboard did not deploy.
   * Upgrading recreates the container against an existing data volume, so a
   * mismatch here would attach someone else's container to Hermes' data.
   */
  public ManagedContainerSpec inspectManaged(String url, String containerId) {
    DockerClient client = clients.forUrl(url);
    return inspectManaged(client, containerId);
  }

  private ManagedContainerSpec inspectManaged(DockerClient client, String containerId) {
    InspectContainerResponse inspected = client.inspectContainerCmd(containerId).exec();
    ContainerConfig config = inspected.getConfig();
    Map<String, String> labels = config == null || config.getLabels() == null
        ? Map.of() : config.getLabels();

    if (!"true".equals(labels.get("mc.managed"))) {
      throw new IllegalArgumentException("not a Mission Control-managed container");
    }
    String dataVolume = labels.get("mc.dataVolume");
    if (dataVolume == null || !dataVolume.startsWith("mc-hermes-")) {
      throw new IllegalArgumentException("container has no recorded managed data volume");
    }
    String[] imageParts = splitImage(config.getImage());
    if (!normalizeRepository(imageParts[0]).equals(normalizeRepository(props.hermesImage()))) {
      throw new IllegalArgumentException("container does not run the configured Hermes image");
    }

    String name = inspected.getName() == null ? "" : inspected.getName();
    if (name.startsWith("/")) name = name.substring(1);

    HostConfig hostConfig = inspected.getHostConfig();
    String primaryNetwork = hostConfig == null || hostConfig.getNetworkMode() == null
        ? null : hostConfig.getNetworkMode();

    Map<String, List<String>> extraNetworks = new LinkedHashMap<>();
    var settings = inspected.getNetworkSettings();
    if (settings != null && settings.getNetworks() != null) {
      for (var entry : settings.getNetworks().entrySet()) {
        // the primary and the built-ins come back with the new container; only
        // user-defined networks (notably the managed MCP one) need reattaching
        if (entry.getKey().equals(primaryNetwork) || BUILTIN_NETWORKS.contains(entry.getKey())) {
          continue;
        }
        List<String> aliases = entry.getValue() == null || entry.getValue().getAliases() == null
            ? List.of()
            : entry.getValue().getAliases().stream()
                .filter(alias -> !containerId.startsWith(alias))   // drop the auto short-id alias
                .toList();
        extraNetworks.put(entry.getKey(), aliases);
      }
    }

    var state = inspected.getState();
    return new ManagedContainerSpec(
        inspected.getId(), name, imageParts[1], inspected.getImageId(), labels,
        hostConfig == null || hostConfig.getBinds() == null ? List.of() : List.of(hostConfig.getBinds()),
        hostConfig == null ? null : hostConfig.getRestartPolicy(),
        config.getCmd() == null ? null : List.of(config.getCmd()),
        config.getEntrypoint() == null ? null : List.of(config.getEntrypoint()),
        config.getEnv() == null ? null : List.of(config.getEnv()),
        config.getUser(), config.getWorkingDir(),
        primaryNetwork, extraNetworks,
        state != null && Boolean.TRUE.equals(state.getRunning()),
        dataVolume);
  }

  /**
   * Moves a managed container onto another tag of the Hermes image, keeping its
   * name, labels, networks and — crucially — its data volume, so profiles, souls,
   * skills and credentials survive.
   *
   * <p>The old container is renamed aside rather than removed, and is only
   * dropped once the replacement passes its readiness checks. That keeps a real
   * rollback target: the original container object, its id, and its logs.
   */
  public UpgradeResult upgrade(String url, String containerId, String version) {
    DockerClient client = clients.forUrl(url);
    ManagedContainerSpec spec = inspectManaged(client, containerId);

    String tag = version == null || version.isBlank() ? "latest" : version;
    String repository = hermesRepository();
    String image = repository + ":" + tag;

    // Pull before touching the running container: a bad tag or an unreachable
    // registry then costs nothing, instead of leaving the Agent stopped.
    String targetImageId = imageIdOf(client, image);
    if (targetImageId == null) {
      pull(clients.streamingForUrl(url), repository, tag);
      targetImageId = imageIdOf(client, image);
    }
    if (tag.equals(spec.tag()) && targetImageId != null && targetImageId.equals(spec.imageId())) {
      throw new ResourceConflictException("already running " + tag);
    }

    if (spec.wasRunning()) {
      try {
        client.stopContainerCmd(spec.id()).withTimeout(10).exec();
      } catch (NotModifiedException alreadyStopped) {
        // raced with a manual stop — the desired state is what matters
      } catch (RuntimeException failedStop) {
        // The daemon may well have killed it before failing to answer, and an explicitly
        // stopped container is not covered by its restart policy — so without this the
        // Agent stays down indefinitely with nothing putting it back. Same exposure the
        // rename below was already pulled inside the guard for.
        try {
          client.startContainerCmd(spec.id()).exec();
        } catch (RuntimeException restore) {
          failedStop.addSuppressed(restore);
        }
        throw failedStop;
      }
    }

    String parkedName = spec.name() + UPGRADE_SUFFIX + shortToken(spec.id());

    String newContainerId = null;
    UpgradeResult result;
    try {
      // inside the rollback guard: the container is already stopped by this point, so a
      // rename that fails (a leftover name from an earlier crashed upgrade, a daemon blip)
      // would otherwise leave the Agent down with nothing putting it back
      client.renameContainerCmd(spec.id()).withName(parkedName).exec();

      HostConfig hostConfig = HostConfig.newHostConfig().withBinds(spec.binds().toArray(new Bind[0]));
      if (spec.restartPolicy() != null) hostConfig.withRestartPolicy(spec.restartPolicy());
      if (spec.primaryNetwork() != null) hostConfig.withNetworkMode(spec.primaryNetwork());

      var create = client.createContainerCmd(image)
          .withName(spec.name())
          .withLabels(spec.labels())
          .withHostConfig(hostConfig);
      if (spec.cmd() != null) create.withCmd(spec.cmd());
      if (spec.entrypoint() != null) create.withEntrypoint(spec.entrypoint());
      if (spec.env() != null) create.withEnv(spec.env());
      if (spec.user() != null && !spec.user().isBlank()) create.withUser(spec.user());
      if (spec.workingDir() != null && !spec.workingDir().isBlank()) {
        create.withWorkingDir(spec.workingDir());
      }
      newContainerId = create.exec().getId();

      for (var network : spec.extraNetworks().entrySet()) {
        connectNetwork(url, newContainerId, network.getKey(), network.getValue());
      }

      if (!spec.wasRunning()) {
        // a container someone deliberately parked comes back parked
        result = new UpgradeResult(spec.id(), newContainerId, spec.tag(), tag, false);
      } else {
        client.startContainerCmd(newContainerId).exec();
        // no seed profiles: they already exist in the reattached volume, and the
        // mc.profiles label is a stale record of the original deploy
        validateDeployment(url, client, newContainerId, List.of());
        result = new UpgradeResult(spec.id(), newContainerId, spec.tag(), tag, true);
      }
    } catch (RuntimeException failure) {
      rollbackUpgrade(client, spec, newContainerId, failure);
      throw failure;
    }

    // Outside the guard: the replacement is created, started and validated, so the upgrade
    // has succeeded. Removing the parked original is cleanup — if it fails transiently,
    // rolling back would destroy a working new container and restart the old image.
    try {
      client.removeContainerCmd(spec.id()).withForce(true).exec();
    } catch (RuntimeException leftover) {
      log.warn("upgraded {} but could not remove the parked container {}: {}",
          spec.name(), parkedName, leftover.getMessage());
    }
    return result;
  }

  /**
   * Restores the parked container after a failed upgrade. Best effort — never masks the cause.
   *
   * <p>The replacement must be confirmed gone before the original is started again. Both
   * containers were created from the same binds, so both mount the same managed data volume
   * at /opt/data and both run {@code gateway run}: starting the original while the
   * replacement survives puts two Hermes gateways on one profile tree.
   */
  private void rollbackUpgrade(
      DockerClient client, ManagedContainerSpec spec, String newContainerId, RuntimeException failure) {
    boolean replacementGone = true;
    if (newContainerId != null) {
      try {
        client.removeContainerCmd(newContainerId).withForce(true).exec();
      } catch (NotFoundException alreadyGone) {
        // nothing to undo
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
        replacementGone = !containerExists(client, newContainerId);
      }
    }

    try {
      client.renameContainerCmd(spec.id()).withName(spec.name()).exec();
    } catch (RuntimeException cleanup) {
      failure.addSuppressed(cleanup);
    }

    if (!replacementGone) {
      log.error("upgrade of {} failed and its replacement {} could not be removed — leaving the "
          + "original stopped rather than running two gateways against {}",
          spec.name(), newContainerId, spec.dataVolume());
      return;
    }
    if (spec.wasRunning()) {
      try {
        client.startContainerCmd(spec.id()).exec();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
    }
  }

  /** Whether the daemon still knows this container, for deciding if a cleanup really landed. */
  private static boolean containerExists(DockerClient client, String containerId) {
    try {
      client.inspectContainerCmd(containerId).exec();
      return true;
    } catch (NotFoundException gone) {
      return false;
    } catch (RuntimeException unknown) {
      return true;   // cannot prove it is gone — assume the unsafe case
    }
  }

  private static String imageIdOf(DockerClient client, String image) {
    try {
      return client.inspectImageCmd(image).exec().getId();
    } catch (NotFoundException absent) {
      return null;
    }
  }

  private static String shortToken(String containerId) {
    return containerId == null || containerId.length() < 8
        ? "00000000" : containerId.substring(0, 8);
  }

  /** True for a container parked mid-upgrade — visible only via the unfiltered listing. */
  static boolean isUpgradeLeftover(String name) {
    return name != null && name.matches(".*" + UPGRADE_SUFFIX + "[0-9a-f]{8}$");
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
      String url, DockerClient client, String image, HostConfig hostConfig,
      List<String> command, String operation) {
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
        pull(clients.streamingForUrl(url), parts[0], parts[1]);
        helper = client.createContainerCmd(image)
            .withLabels(Map.of("mc.bootstrap", "true"))
            .withHostConfig(hostConfig)
            .withCmd(command)
            .exec();
      }
      helperId = helper.getId();
      client.startContainerCmd(helperId).exec();
      // /wait emits nothing until the container exits, so it must not run on a client
      // carrying a socket timeout — the 90s budget below is the real bound
      var callback = clients.streamingForUrl(url).waitContainerCmd(helperId).start();
      try {
        Integer exitCode = callback.awaitStatusCode(90, TimeUnit.SECONDS);
        if (exitCode == null) throw new UpstreamUnavailableException(operation + " timed out");
        if (exitCode != 0) throw new UpstreamUnavailableException(operation + " failed with exit code " + exitCode);
      } catch (DockerClientException exhausted) {
        // how docker-java actually reports an expired wait: a DockerClientException, which is
        // not a DockerException, so it would otherwise reach the advice's catch-all as a 500
        throw new UpstreamUnavailableException(operation + " timed out", exhausted);
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
          if (failure != null) {
            failure.addSuppressed(cleanup);
          } else {
            // the step itself succeeded. Failing the deploy over a transient cleanup error
            // would roll back working state — and the surviving helper still mounts the data
            // volume, so the rollback could not remove that either. It is labelled
            // mc.bootstrap and can be reaped later.
            log.warn("{} succeeded but its bootstrap helper {} could not be removed: {}",
                operation, helperId, cleanup.getMessage());
          }
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
        throw new UpstreamUnavailableException("image pull timed out: " + repository + ":" + tag);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UpstreamUnavailableException("image pull interrupted", e);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new UpstreamUnavailableException("image pull failed: " + e.getMessage(), e);
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
      throw new UpstreamUnavailableException(
          "container removed but managed data volume could not be removed: " + volumeName, e);
    }
  }
}
