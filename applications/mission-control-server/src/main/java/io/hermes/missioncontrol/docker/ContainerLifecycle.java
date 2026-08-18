package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import java.util.Map;
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

  private final DockerClients clients;

  public ContainerLifecycle(DockerClients clients) {
    this.clients = clients;
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
