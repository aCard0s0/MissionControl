package io.hermes.missioncontrol.hosts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hosts")
public class HostsController {

  public record CreateHostRequest(@NotBlank String name, @NotBlank String url) {}

  private final HostService hosts;

  public HostsController(HostService hosts) {
    this.hosts = hosts;
  }

  @GetMapping
  public List<DockerHostDto> list() {
    return hosts.list();
  }

  @PostMapping
  public DockerHostDto add(@Valid @RequestBody CreateHostRequest request) {
    return hosts.add(request.name().trim(), request.url().trim());
  }

  @PostMapping("/{id}/check")
  public DockerHostDto check(@PathVariable String id) {
    return hosts.check(id);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable String id) {
    hosts.delete(id);
  }
}
