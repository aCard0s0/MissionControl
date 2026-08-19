package io.hermes.missioncontrol.fleet;

import io.hermes.missioncontrol.docker.ContainerDto;
import io.hermes.missioncontrol.docker.ContainerUpdateService;
import io.hermes.missioncontrol.docker.DeployRequest;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.LogLineDto;
import io.hermes.missioncontrol.docker.StatsDto;
import io.hermes.missioncontrol.docker.UpdateContainerRequest;
import io.hermes.missioncontrol.hosts.HostService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers")
public class ContainersController {

  private static final Logger log = LoggerFactory.getLogger(ContainersController.class);

  private final DockerGateway docker;
  private final HostService hosts;
  private final ContainerUpdateService updates;

  public ContainersController(
      DockerGateway docker, HostService hosts, ContainerUpdateService updates) {
    this.docker = docker;
    this.hosts = hosts;
    this.updates = updates;
  }

  /**
   * Inventory across hosts. Filtered to Hermes-related containers unless
   * all=true; hosts that fail to answer are skipped (their status is already
   * visible on /api/hosts).
   *
   * <p>The only endpoint here that does not resolve through
   * {@code HostService.requireConnected}: it is already iterating listed rows and
   * filtering on the status they carry, so it builds each ref from the row it holds
   * rather than asking for the same verdict a second time.
   */
  @GetMapping
  public List<ContainerDto> list(
      @RequestParam(required = false) String hostId,
      @RequestParam(defaultValue = "false") boolean all) {
    List<ContainerDto> result = new ArrayList<>();
    for (var host : hosts.list()) {
      if (hostId != null && !hostId.equals(host.id())) continue;
      if (!"connected".equals(host.status())) continue;
      try {
        result.addAll(docker.listContainers(new DockerHostRef(host.id(), host.url()), all));
      } catch (Exception e) {
        log.warn("listing containers on {} failed: {}", host.id(), e.getMessage());
      }
    }
    return result;
  }

  // Every endpoint below resolves through requireConnected, so a daemon that is down is
  // reported as a 503 before the container is touched. They used to take the host row's url
  // unprobed, which left the same outage surfacing as a 502 'docker daemon error' — the
  // failure ImagesController's comment already said every such endpoint should avoid.
  @GetMapping("/{hostId}/{id}/stats")
  public StatsDto stats(@PathVariable String hostId, @PathVariable String id) {
    return docker.stats(hosts.requireConnected(hostId), id);
  }

  @GetMapping("/{hostId}/{id}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int tail) {
    return docker.logs(hosts.requireConnected(hostId), id, tail);
  }

  @PostMapping
  public Map<String, String> deploy(@Valid @RequestBody DeployRequest request) {
    String containerId = docker.deploy(
        hosts.requireConnected(request.hostId()),
        request.name(), request.version(), request.profiles());
    return Map.of("id", containerId);
  }

  @PostMapping("/{hostId}/{id}/start")
  public void start(@PathVariable String hostId, @PathVariable String id) {
    docker.start(hosts.requireConnected(hostId), id);
  }

  @PostMapping("/{hostId}/{id}/stop")
  public void stop(@PathVariable String hostId, @PathVariable String id) {
    docker.stop(hosts.requireConnected(hostId), id);
  }

  /**
   * Recreates the container on another image tag, reusing its data volume. The
   * container id changes, so the replacement's id is returned.
   */
  @PostMapping("/{hostId}/{id}/update")
  public Map<String, String> update(
      @PathVariable String hostId,
      @PathVariable String id,
      @Valid @RequestBody UpdateContainerRequest request) {
    return Map.of("id", updates.update(hosts.requireConnected(hostId), id, request.version()));
  }

  @DeleteMapping("/{hostId}/{id}")
  public void remove(@PathVariable String hostId, @PathVariable String id) {
    docker.remove(hosts.requireConnected(hostId), id);
  }
}
