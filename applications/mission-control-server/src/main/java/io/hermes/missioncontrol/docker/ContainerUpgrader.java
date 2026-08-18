package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.HostConfig;
import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Moving a managed container onto another tag of the Hermes image.
 *
 * <p>Split out of {@link DockerGateway} because an upgrade is the one operation that can
 * destroy an Agent: it recreates the container against the existing data volume, so it
 * refuses anything this dashboard did not deploy, parks the original rather than removing
 * it, and only drops it once the replacement has passed its readiness checks.
 */
@Component
public class ContainerUpgrader {

  private static final Logger log = LoggerFactory.getLogger(ContainerUpgrader.class);

  private final DockerClients clients;
  private final AppProperties props;
  private final ImageStore images;
  private final DeploymentReadiness readiness;
  private final DockerNetworks networks;

  public ContainerUpgrader(
      DockerClients clients, AppProperties props, ImageStore images,
      DeploymentReadiness readiness, DockerNetworks networks) {
    this.clients = clients;
    this.props = props;
    this.images = images;
    this.readiness = readiness;
    this.networks = networks;
  }

  /**
   * Inspects a container and refuses anything this dashboard did not deploy.
   * Upgrading recreates the container against an existing data volume, so a
   * mismatch here would attach someone else's container to Hermes' data.
   */
  public ManagedContainerSpec inspectManaged(String url, String containerId) {
    return inspectManaged(clients.forUrl(url), containerId);
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
    String[] imageParts = ImageRef.splitImage(config.getImage());
    if (!ImageRef.normalizeRepository(imageParts[0])
        .equals(ImageRef.normalizeRepository(props.hermesImage()))) {
      throw new IllegalArgumentException("container does not run the configured Hermes image");
    }

    String name = inspected.getName() == null ? "" : inspected.getName();
    if (name.startsWith("/")) name = name.substring(1);

    HostConfig hostConfig = inspected.getHostConfig();
    String primaryNetwork = hostConfig == null || hostConfig.getNetworkMode() == null
        ? null : hostConfig.getNetworkMode();

    var state = inspected.getState();
    return new ManagedContainerSpec(
        inspected.getId(), name, imageParts[1], inspected.getImageId(), labels,
        hostConfig == null || hostConfig.getBinds() == null ? List.of() : List.of(hostConfig.getBinds()),
        hostConfig == null ? null : hostConfig.getRestartPolicy(),
        config.getCmd() == null ? null : List.of(config.getCmd()),
        config.getEntrypoint() == null ? null : List.of(config.getEntrypoint()),
        config.getEnv() == null ? null : List.of(config.getEnv()),
        config.getUser(), config.getWorkingDir(),
        primaryNetwork, reattachableNetworks(inspected, primaryNetwork, containerId),
        state != null && Boolean.TRUE.equals(state.getRunning()),
        dataVolume);
  }

  /** The networks the replacement has to be connected to by hand: the primary and the
   *  built-ins come back with a new container, only user-defined ones (notably the managed
   *  MCP network) do not. */
  private static Map<String, List<String>> reattachableNetworks(
      InspectContainerResponse inspected, String primaryNetwork, String containerId) {
    Map<String, List<String>> extraNetworks = new LinkedHashMap<>();
    var settings = inspected.getNetworkSettings();
    if (settings == null || settings.getNetworks() == null) return extraNetworks;
    for (var entry : settings.getNetworks().entrySet()) {
      if (entry.getKey().equals(primaryNetwork)
          || DockerNetworks.BUILTIN_NETWORKS.contains(entry.getKey())) {
        continue;
      }
      List<String> aliases = entry.getValue() == null || entry.getValue().getAliases() == null
          ? List.of()
          : entry.getValue().getAliases().stream()
              .filter(alias -> !containerId.startsWith(alias))   // drop the auto short-id alias
              .toList();
      extraNetworks.put(entry.getKey(), aliases);
    }
    return extraNetworks;
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

    String tag = ImageStore.tagOf(version);
    String repository = images.hermesRepository();
    String image = repository + ":" + tag;

    // Pull before touching the running container: a bad tag or an unreachable
    // registry then costs nothing, instead of leaving the Agent stopped.
    String targetImageId = ImageStore.imageIdOf(client, image);
    if (targetImageId == null) {
      images.pull(url, repository, tag);
      targetImageId = ImageStore.imageIdOf(client, image);
    }
    if (tag.equals(spec.tag()) && targetImageId != null && targetImageId.equals(spec.imageId())) {
      throw new ResourceConflictException("already running " + tag);
    }

    if (spec.wasRunning()) stopBeforeReplace(client, spec);

    String parkedName = ParkedContainerName.of(spec.name(), spec.id());

    String newContainerId = null;
    UpgradeResult result;
    try {
      // inside the rollback guard: the container is already stopped by this point, so a
      // rename that fails (a leftover name from an earlier crashed upgrade, a daemon blip)
      // would otherwise leave the Agent down with nothing putting it back
      client.renameContainerCmd(spec.id()).withName(parkedName).exec();

      newContainerId = createReplacement(client, spec, image);

      for (var network : spec.extraNetworks().entrySet()) {
        networks.connect(url, newContainerId, network.getKey(), network.getValue());
      }

      if (!spec.wasRunning()) {
        // a container someone deliberately parked comes back parked
        result = new UpgradeResult(spec.id(), newContainerId, spec.tag(), tag, false);
      } else {
        client.startContainerCmd(newContainerId).exec();
        // no seed profiles: they already exist in the reattached volume, and the
        // mc.profiles label is a stale record of the original deploy
        readiness.validate(url, client, newContainerId, List.of());
        result = new UpgradeResult(spec.id(), newContainerId, spec.tag(), tag, true);
      }
    } catch (RuntimeException failure) {
      rollback(client, spec, newContainerId, failure);
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

  private static void stopBeforeReplace(DockerClient client, ManagedContainerSpec spec) {
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

  private static String createReplacement(
      DockerClient client, ManagedContainerSpec spec, String image) {
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
    return create.exec().getId();
  }

  /**
   * Restores the parked container after a failed upgrade. Best effort — never masks the cause.
   *
   * <p>The replacement must be confirmed gone before the original is started again. Both
   * containers were created from the same binds, so both mount the same managed data volume
   * at /opt/data and both run {@code gateway run}: starting the original while the
   * replacement survives puts two Hermes gateways on one profile tree.
   */
  private void rollback(
      DockerClient client, ManagedContainerSpec spec, String newContainerId,
      RuntimeException failure) {
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
}
