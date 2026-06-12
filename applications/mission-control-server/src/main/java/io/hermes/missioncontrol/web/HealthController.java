package io.hermes.missioncontrol.web;

import io.hermes.missioncontrol.AppProperties;
import io.hermes.missioncontrol.hosts.HostService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  private final AppProperties props;
  private final HostService hosts;

  public HealthController(AppProperties props, HostService hosts) {
    this.props = props;
    this.hosts = hosts;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of(
        "status", "ok",
        "version", props.version(),
        "dockerConnected", hosts.isLocalDaemonConnected());
  }
}
