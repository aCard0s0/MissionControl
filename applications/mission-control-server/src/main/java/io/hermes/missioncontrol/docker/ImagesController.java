package io.hermes.missioncontrol.docker;

import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/images")
public class ImagesController {

  private final DockerGateway docker;
  private final HostService hosts;

  public ImagesController(DockerGateway docker, HostService hosts) {
    this.docker = docker;
    this.hosts = hosts;
  }

  @GetMapping("/tags")
  public ImageTagsDto tags(@RequestParam String hostId) {
    DockerHostDto host = hosts.check(hostId);
    if (!"connected".equals(host.status())) {
      throw new IllegalStateException("docker host not connected");
    }
    return docker.imageTags(host.url());
  }
}
