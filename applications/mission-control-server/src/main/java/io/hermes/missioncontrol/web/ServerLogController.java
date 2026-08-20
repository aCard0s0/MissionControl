package io.hermes.missioncontrol.web;

import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.LogLineDto;
import java.lang.management.ManagementFactory;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's own log tail.
 *
 * <p>Answers {@link LogLineDto}, the same shape the container tail already returns, so the
 * frontend renders both through one component instead of gaining a second log model.
 */
@RestController
@RequestMapping("/api/server")
public class ServerLogController {

  private static final int DEFAULT_TAIL = 200;

  private final ServerLogBuffer buffer;
  private final AppProperties props;

  public ServerLogController(ServerLogBuffer buffer, AppProperties props) {
    this.buffer = buffer;
    this.props = props;
  }

  /** @param level one of error|warn|info|debug, or absent/all for everything retained */
  @GetMapping("/logs")
  public List<LogLineDto> logs(
      @RequestParam(defaultValue = "" + DEFAULT_TAIL) int tail,
      @RequestParam(required = false) String level) {
    return buffer.tail(tail, level);
  }

  /**
   * What the log page shows in its header. Separate from {@code /health}, which the launcher
   * polls and which should not grow fields for one page's benefit.
   */
  @GetMapping("/info")
  public ServerInfoDto info() {
    return new ServerInfoDto(
        props.version(),
        ServerLogBuffer.CAPACITY,
        ManagementFactory.getRuntimeMXBean().getStartTime());
  }
}
