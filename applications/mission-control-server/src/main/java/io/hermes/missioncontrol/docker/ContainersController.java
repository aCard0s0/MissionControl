package io.hermes.missioncontrol.docker;

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

  public ContainersController(DockerGateway docker, HostService hosts) {
    this.docker = docker;
    this.hosts = hosts;
  }

  /**
   * Inventory across hosts. Filtered to Hermes-related containers unless
   * all=true; hosts that fail to answer are skipped (their status is already
   * visible on /api/hosts).
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
        result.addAll(docker.listContainers(host.url(), host.id(), all));
      } catch (Exception e) {
        log.warn("listing containers on {} failed: {}", host.id(), e.getMessage());
      }
    }
    return result;
  }

  @GetMapping("/{hostId}/{id}/stats")
  public StatsDto stats(@PathVariable String hostId, @PathVariable String id) {
    return docker.stats(hosts.urlOf(hostId), id);
  }

  @GetMapping("/{hostId}/{id}/logs")
  public List<LogLineDto> logs(
      @PathVariable String hostId,
      @PathVariable String id,
      @RequestParam(defaultValue = "100") int tail) {
    return docker.logs(hosts.urlOf(hostId), id, tail);
  }

  @PostMapping
  public Map<String, String> deploy(@Valid @RequestBody DeployRequest request) {
    String containerId = docker.deploy(
        hosts.urlOf(request.hostId()), request.hostId(),
        request.name(), request.version(), request.profiles());
    return Map.of("id", containerId);
  }

  @PostMapping("/{hostId}/{id}/start")
  public void start(@PathVariable String hostId, @PathVariable String id) {
    docker.start(hosts.urlOf(hostId), id);
  }

  @PostMapping("/{hostId}/{id}/stop")
  public void stop(@PathVariable String hostId, @PathVariable String id) {
    docker.stop(hosts.urlOf(hostId), id);
  }

  @DeleteMapping("/{hostId}/{id}")
  public void remove(@PathVariable String hostId, @PathVariable String id) {
    docker.remove(hosts.urlOf(hostId), id);
  }
}
