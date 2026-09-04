package io.hermes.missioncontrol.docker;

import static io.hermes.missioncontrol.docker.ContainerIds.shortId;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Start, stop and permanent removal of a container.
 *
 * <p>Split out of {@link DockerGateway} for the removal path: dropping a Mission
 * Control-managed container also drops the data volume holding its profiles, souls, skills
 * and credentials, so the label check that decides whether that happens is the whole point
 * of this class.
 */
@Component
public class ContainerLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ContainerLifecycle.class);

  private final DockerClients clients;
  private final List<ContainerWork> work;

  /**
   * {@code work} is lazy, and is not copied: the implementation is {@code HermesProfiles},
   * which reaches this class again through the MCP catalog and the host registry. Resolving
   * it at construction closes that cycle; resolving it on the first stop does not.
   */
  public ContainerLifecycle(DockerClients clients, @Lazy List<ContainerWork> work) {
    this.clients = clients;
    this.work = work;
  }

  public void start(DockerHostRef host, String containerId) {
    clients.forUrl(host.url()).startContainerCmd(containerId).exec();
    log.info("started container {} on {}", shortId(containerId), host.id());
  }

  public void stop(DockerHostRef host, String containerId) {
    assertNothingInFlight(containerId);
    clients.forUrl(host.url()).stopContainerCmd(containerId).withTimeout(10).exec();
    log.info("stopped container {} on {}", shortId(containerId), host.id());
  }

  /**
   * Refuses, as a 409, while something Mission Control started inside the container has not
   * finished — a profile create between its first exec and its last write. The daemon would
   * stop the container happily; the create would then fail its next exec and roll the
   * half-made profile back, which from the operator's side is a profile that vanished.
   * The stop dialog names the same profiles from the activity route, so the UI path warns
   * first and this is the guard behind it, for the API and the upgrade path.
   */
  public void assertNothingInFlight(String containerId) {
    for (ContainerWork w : work) {
      List<String> creating = w.creating(containerId);
      if (!creating.isEmpty()) {
        throw new ResourceConflictException("profile " + String.join(", ", creating)
            + " is still being created in " + shortId(containerId)
            + " — stopping now would fail that and delete it; wait for the create to finish");
      }
    }
  }

  /**
   * Logged at WARN, and before the fact rather than after: this is the one operation in the
   * application that destroys data it cannot recreate — the volume holds the Agent's
   * profiles, souls, skills and credentials. If the process dies between the two daemon
   * calls, the line naming the volume is the only record of what was being dropped.
   */
  public void remove(DockerHostRef host, String containerId) {
    DockerClient client = clients.forUrl(host.url());
    var inspected = client.inspectContainerCmd(containerId).exec();
    Map<String, String> labels = inspected.getConfig() == null || inspected.getConfig().getLabels() == null
        ? Map.of() : inspected.getConfig().getLabels();
    String volumeName = ManagedContainer.dataVolumeOf(labels);
    log.warn("removing container {} on {}{}", shortId(containerId), host.id(),
        volumeName == null ? "" : " and its managed data volume " + volumeName);
    client.removeContainerCmd(containerId).withForce(true).exec();
    if (volumeName == null) return;
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
