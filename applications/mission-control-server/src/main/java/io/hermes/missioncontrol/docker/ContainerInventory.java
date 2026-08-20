package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Version;
import io.hermes.missioncontrol.config.AppProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the fleet view shows: the daemon's own version, and the containers on it that are
 * Mission Control's business.
 *
 * <p>Split out of {@link DockerGateway} because the filtering is the subtle part. Every
 * {@code continue} below is a container deliberately hidden, and each has cost a live Agent
 * its card at some point — a moved floating tag, a stranded bootstrap helper, a container
 * parked by an interrupted upgrade.
 */
@Component
public class ContainerInventory {

  private static final Logger log = LoggerFactory.getLogger(ContainerInventory.class);

  /**
   * Containers already reported as hidden from the fleet, as {@code hostId/name}, so each is
   * reported once rather than once per listing.
   *
   * <p>This is what the exclusion warnings are for: an operator looking for a container that
   * is not on the dashboard. They describe a standing property of a container, not an event,
   * and the fleet view polls every 10 seconds — so unguarded they re-fire forever. On a
   * machine running the managed MCP stack that was 572 of 612 log lines, and it buried the
   * upgrade and terminal failures the file existed to surface.
   *
   * <p>Pruned in {@link #listContainers} against the names that daemon still reports, so a
   * container that goes away and comes back is reported again — the second occurrence is
   * genuinely new information. Keyed by host as well as name because container names are only
   * unique per daemon, and listing one host must not re-arm another host's report.
   */
  private final Set<String> reportedExclusions = ConcurrentHashMap.newKeySet();

  private final DockerClients clients;
  private final AppProperties props;
  private final ImageStore images;

  public ContainerInventory(DockerClients clients, AppProperties props, ImageStore images) {
    this.clients = clients;
    this.props = props;
    this.images = images;
  }

  public DaemonInfo ping(String url) {
    DockerClient client = clients.forUrl(url);
    long t0 = System.nanoTime();
    client.pingCmd().exec();
    long latencyMs = Math.max(0, (System.nanoTime() - t0) / 1_000_000);
    Version version = client.versionCmd().exec();
    return new DaemonInfo("Docker " + version.getVersion(), version.getApiVersion(), latencyMs);
  }

  public List<ContainerDto> listContainers(DockerHostRef host, boolean includeAll) {
    DockerClient client = clients.forUrl(host.url());
    List<Container> containers = client.listContainersCmd()
        .withShowAll(true)
        .withShowSize(true)
        .exec();

    List<ContainerDto> result = new ArrayList<>();
    Set<String> present = new HashSet<>();
    // containers on a host overwhelmingly share a handful of images, so the digest lookup
    // is resolved once per image rather than once per container
    Map<String, String> digests = new HashMap<>();
    for (Container c : containers) {
      present.add(exclusionKey(host.id(), primaryName(c)));
      if (!includeAll && !isFleetMember(host.id(), c)) continue;
      result.add(toDto(client, c, host.id(), digests));
    }
    // every call lists the whole daemon (withShowAll), so anything this host reported before
    // and does not report now is gone; other hosts' entries are left alone
    reportedExclusions.removeIf(
        key -> key.startsWith(host.id() + "/") && !present.contains(key));
    return result;
  }

  /** True the first time a container is excluded, false while that exclusion stands. */
  private boolean firstReportOf(String hostId, String name) {
    return reportedExclusions.add(exclusionKey(hostId, name));
  }

  private static String exclusionKey(String hostId, String name) {
    return hostId + "/" + name;
  }

  /** Whether the filtered fleet view shows this container. Every rejection is logged or
   *  commented at the point it is made, because each hid a container an operator looked for. */
  private boolean isFleetMember(String hostId, Container c) {
    String name = primaryName(c);
    String image = c.getImage() == null ? "" : c.getImage();
    Map<String, String> labels = c.getLabels() == null ? Map.of() : c.getLabels();

    if (ParkedContainerName.isUpgradeLeftover(name)) {
      // a daemon crash mid-upgrade can strand the parked original; keep it out
      // of the fleet view but reachable through ?all=true
      if (firstReportOf(hostId, name)) {
        log.warn("hiding {} from the fleet: left parked by an interrupted upgrade "
            + "(still listed by ?all=true)", name);
      }
      return false;
    }
    if (ManagedContainer.isBootstrap(labels)) {
      // a short-lived seeding helper, or one stranded by a failed deploy — never an Agent
      return false;
    }
    // A container we deployed is ours whatever its image reference reads as, so it is
    // never matched on that reference. The Engine substitutes the raw image ID once a
    // reference stops resolving — moving the `latest` tag during one Agent's upgrade
    // does exactly that to every other Agent on that tag — and an ID parses as
    // repository "sha256", matching nothing and dropping a live Agent out of the fleet.
    if (ManagedContainer.isManaged(labels)) return true;

    if (isImageIdReference(image)) {
      if (firstReportOf(hostId, name)) {
        log.warn("hiding {} from the fleet: it reports an image id rather than a reference "
            + "and carries no Mission Control label", name);
      }
      return false;
    }
    String hermesRepo = images.normalizedHermesRepository();
    if (!hermesRepo.isEmpty()) {
      return hermesRepo.equals(ImageRef.normalizeRepository(ImageRef.splitImage(image)[0]));
    }
    String filter = props.containerFilter() == null
        ? "" : props.containerFilter().toLowerCase(Locale.ROOT);
    if (filter.isEmpty()) return true;
    return image.toLowerCase(Locale.ROOT).contains(filter)
        || name.toLowerCase(Locale.ROOT).contains(filter);
  }

  private ContainerDto toDto(
      DockerClient client, Container c, String hostId, Map<String, String> digestCache) {
    String name = primaryName(c);
    String[] imageParts = ImageRef.splitImage(c.getImage());
    if (isImageIdReference(c.getImage())) {
      // "sha256:e5b3…" would otherwise render as repository "sha256" with the hex as the
      // version. Report the repository the container is known to run and leave the tag
      // blank — the reference it was created from is genuinely no longer recoverable here.
      imageParts = new String[]{images.hermesRepository(), ""};
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
    List<String> profiles = ManagedContainer.seedProfilesOf(labels);

    return new ContainerDto(
        c.getId(), c.getId().substring(0, Math.min(7, c.getId().length())), name, hostId,
        status, imageParts[0], imageParts[1], imageDigest(client, c, digestCache),
        startedAt, sizeGb, profiles);
  }

  /**
   * The registry manifest digest of the image a container runs, from its {@code RepoDigests}.
   *
   * <p>Best effort by design: an image built locally and never pushed has no repo digest, and
   * an unreachable daemon is already reported by everything else on this path. Both answer
   * null, which the dashboard reads as "cannot tell" rather than "up to date" — the one thing
   * this must never do is manufacture a false update prompt.
   */
  private static String imageDigest(
      DockerClient client, Container c, Map<String, String> cache) {
    String imageId = c.getImageId();
    if (imageId == null || imageId.isBlank()) return null;
    String digest = cache.computeIfAbsent(imageId, id -> {
      try {
        List<String> repoDigests = client.inspectImageCmd(id).exec().getRepoDigests();
        if (repoDigests == null) return "";
        // entries read repository@sha256:… — the digest is what compares against a registry
        return repoDigests.stream()
            .map(entry -> entry.substring(entry.indexOf('@') + 1))
            .filter(value -> value.startsWith("sha256:"))
            .findFirst()
            .orElse("");
      } catch (RuntimeException unavailable) {
        return "";
      }
    });
    return digest.isBlank() ? null : digest;
  }

  private static String primaryName(Container c) {
    String[] names = c.getNames();
    if (names == null || names.length == 0) return "?";
    return names[0].startsWith("/") ? names[0].substring(1) : names[0];
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

  static String mapStatus(String state, String statusText) {
    String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
    if ("running".equals(s)) {
      return statusText != null && statusText.contains("(unhealthy)") ? "unhealthy" : "running";
    }
    if ("restarting".equals(s)) return "unhealthy";
    if ("exited".equals(s) || "created".equals(s) || "paused".equals(s) || "dead".equals(s)) return "stopped";
    return "unknown";
  }
}
