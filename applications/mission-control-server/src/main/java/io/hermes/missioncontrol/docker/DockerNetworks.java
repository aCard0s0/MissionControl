package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.Network;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Attaching a container to a user-defined Docker network.
 *
 * <p>Split out of {@link DockerGateway} because idempotence here is not a nicety: an Agent
 * upgrade reattaches networks, and the managed MCP network is joined on demand from several
 * places, so the same connect can genuinely be in flight twice.
 */
@Component
public class DockerNetworks {

  /** Docker's own networks — a new container joins these without being connected. */
  static final Set<String> BUILTIN_NETWORKS = Set.of("bridge", "host", "none");

  private final DockerClients clients;

  public DockerNetworks(DockerClients clients) {
    this.clients = clients;
  }

  /**
   * Attaches an existing container to a named Docker network. The operation is
   * idempotent, including when another request wins the connect race between
   * our inspection and the Engine call.
   */
  public void connect(DockerHostRef host, String containerId, String networkName) {
    connect(host, containerId, networkName, List.of());
  }

  /** As above, preserving the network aliases a container was reachable under. */
  public void connect(DockerHostRef host, String containerId, String networkName, List<String> aliases) {
    if (networkName == null || networkName.isBlank()) {
      throw new IllegalArgumentException("missing network name");
    }
    DockerClient client = clients.forUrl(host.url());
    if (containerUsesNetwork(client, containerId, networkName)) return;
    String networkId = client.listNetworksCmd().withNameFilter(networkName).exec().stream()
        .filter(network -> networkName.equals(network.getName()))
        .map(Network::getId)
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
}
