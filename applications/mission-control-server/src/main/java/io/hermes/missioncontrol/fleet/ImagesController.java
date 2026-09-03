package io.hermes.missioncontrol.fleet;

import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.docker.ImageCatalogService;
import io.hermes.missioncontrol.docker.ImageTagsDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/images")
public class ImagesController {

  private final ImageCatalogService catalog;

  public ImagesController(ImageCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping("/tags")
  public ImageTagsDto tags(
      @RequestParam("hostId") DockerHostRef host,
      @RequestParam(defaultValue = "true") boolean remote) {
    // The parameter arrives probed, through requireConnected rather than a local status
    // check: a daemon that is down is an upstream failure (503), not a bad request.
    return catalog.tags(host, remote);
  }
}
