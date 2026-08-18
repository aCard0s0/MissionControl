package io.hermes.missioncontrol.fleet;

import io.hermes.missioncontrol.docker.ImageCatalogService;
import io.hermes.missioncontrol.docker.ImageTagsDto;
import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/images")
public class ImagesController {

  private final ImageCatalogService catalog;
  private final HostService hosts;

  public ImagesController(ImageCatalogService catalog, HostService hosts) {
    this.catalog = catalog;
    this.hosts = hosts;
  }

  @GetMapping("/tags")
  public ImageTagsDto tags(
      @RequestParam String hostId,
      @RequestParam(defaultValue = "true") boolean remote) {
    // requireConnected rather than a local status check: a daemon that is down is an
    // upstream failure (503), not a bad request, and every other endpoint that needs a
    // live host reports it that way
    DockerHostDto host = hosts.requireConnected(hostId);
    return catalog.tags(host.url(), remote);
  }
}
