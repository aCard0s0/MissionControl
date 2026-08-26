package io.hermes.missioncontrol.docker;

import static io.hermes.missioncontrol.docker.ContainerIds.shortId;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  public ContainerLifecycle(DockerClients clients) {
    this.clients = clients;
  }

  public void start(DockerHostRef host, String containerId) {
    clients.forUrl(host.url()).startContainerCmd(containerId).exec();
    log.info("started container {} on {}", shortId(containerId), host.id());
  }

  public void stop(DockerHostRef host, String containerId) {
    clients.forUrl(host.url()).stopContainerCmd(containerId).withTimeout(10).exec();
    log.info("stopped container {} on {}", shortId(containerId), host.id());
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
