package io.hermes.missioncontrol.docker;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Thin, stateless gateway over the Docker Engine API. The daemon is the
 * source of truth — nothing here is cached or persisted.
 *
 * <p>This class is the one entry point the rest of the application talks to; each concern
 * behind it is a collaborator of its own:
 *
 * <ul>
 *   <li>{@link ContainerInventory} — the daemon's version, and which containers are the fleet
 *   <li>{@link ContainerStatsReader} — one resource sample per container
 *   <li>{@link ContainerLogReader} — the stdout/stderr tail and its severity rules
 *   <li>{@link DockerNetworks} — idempotent attachment to a user-defined network
 *   <li>{@link ImageStore} — the configured Hermes image, its local tags, and pulls
 *   <li>{@link HermesDeployer} — creating a container, its volume and its seed profiles
 *   <li>{@link ContainerUpgrader} — retagging a managed container, with rollback
 *   <li>{@link ContainerLifecycle} — start, stop, and removal with the data volume
 * </ul>
 */
@Service
public class DockerGateway {

  private final ContainerInventory inventory;
  private final ContainerStatsReader statsReader;
  private final ContainerLogReader logReader;
  private final DockerNetworks networks;
  private final ImageStore images;
  private final HermesDeployer deployer;
  private final ContainerUpgrader upgrader;
  private final ContainerLifecycle lifecycle;

  public DockerGateway(
      ContainerInventory inventory,
      ContainerStatsReader statsReader,
      ContainerLogReader logReader,
      DockerNetworks networks,
      ImageStore images,
      HermesDeployer deployer,
      ContainerUpgrader upgrader,
      ContainerLifecycle lifecycle) {
    this.inventory = inventory;
    this.statsReader = statsReader;
    this.logReader = logReader;
    this.networks = networks;
    this.images = images;
    this.deployer = deployer;
    this.upgrader = upgrader;
    this.lifecycle = lifecycle;
  }

  // ── daemon probing ───────────────────────────────────────────────────────

  public DaemonInfo ping(String url) {
    return inventory.ping(url);
  }

  // ── inventory ────────────────────────────────────────────────────────────

  public List<ContainerDto> listContainers(String url, String hostId, boolean includeAll) {
    return inventory.listContainers(url, hostId, includeAll);
  }

  // ── stats / logs ─────────────────────────────────────────────────────────

  public StatsDto stats(String url, String containerId) {
    return statsReader.stats(url, containerId);
  }

  public List<LogLineDto> logs(String url, String containerId, int tail) {
    return logReader.logs(url, containerId, tail);
  }

  // ── networks ─────────────────────────────────────────────────────────────

  public void connectNetwork(String url, String containerId, String networkName) {
    networks.connect(url, containerId, networkName);
  }

  public void connectNetwork(
      String url, String containerId, String networkName, List<String> aliases) {
    networks.connect(url, containerId, networkName, aliases);
  }

  // ── images ──────────────────────────────────────────────────────────────

  public Set<String> localImageTags(String url) {
    return images.localImageTags(url);
  }

  public String hermesImageRepository() {
    return images.hermesImageRepository();
  }

  // ── lifecycle ────────────────────────────────────────────────────────────

  public String deploy(String url, String hostId, String name, String version, List<String> profiles) {
    return deployer.deploy(url, hostId, name, version, profiles);
  }

  public ManagedContainerSpec inspectManaged(String url, String containerId) {
    return upgrader.inspectManaged(url, containerId);
  }

  public UpgradeResult upgrade(String url, String containerId, String version) {
    return upgrader.upgrade(url, containerId, version);
  }

  public void start(String url, String containerId) {
    lifecycle.start(url, containerId);
  }

  public void stop(String url, String containerId) {
    lifecycle.stop(url, containerId);
  }

  public void remove(String url, String containerId) {
    lifecycle.remove(url, containerId);
  }
}
