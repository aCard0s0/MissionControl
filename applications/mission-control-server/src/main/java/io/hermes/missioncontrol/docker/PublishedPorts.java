package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.model.HostConfig;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Which of a container's ports the daemon publishes on the host, read live.
 *
 * <p>A mapping is create-time and the daemon is its only record — a deploy may have asked for
 * one, an operator may have added one by hand when recreating the container, and neither is
 * remembered anywhere else. So this inspects rather than recalls, which is what lets the
 * webhook page say a route is reachable only when it actually is.
 */
@Component
public class PublishedPorts {

  private final DockerClients clients;

  public PublishedPorts(DockerClients clients) {
    this.clients = clients;
  }

  /** The container ports with at least one host binding; empty when none are published. */
  public Set<Integer> of(DockerHostRef host, String containerId) {
    HostConfig hostConfig =
        clients.forUrl(host.url()).inspectContainerCmd(containerId).exec().getHostConfig();
    Set<Integer> published = new TreeSet<>();
    if (hostConfig == null || hostConfig.getPortBindings() == null) return published;
    hostConfig.getPortBindings().getBindings().forEach((port, bindings) -> {
      if (bindings != null && bindings.length > 0) published.add(port.getPort());
    });
    return published;
  }
}
