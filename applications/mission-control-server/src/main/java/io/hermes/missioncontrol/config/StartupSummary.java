package io.hermes.missioncontrol.config;

import io.hermes.missioncontrol.hosts.DockerHostDto;
import io.hermes.missioncontrol.hosts.HostRepository;
import io.hermes.missioncontrol.hosts.HostService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The one block of startup output this application prints about itself.
 *
 * <p>Spring's own startup lines are switched off ({@code spring.main.log-startup-info}), and
 * so are Tomcat's, Hikari's and the servlet context's. Between them they printed a dozen
 * lines and answered none of the questions a Mission Control operator actually has at boot:
 * whether the Docker socket is mounted, which database file is in use, where the managed MCP
 * Compose files are written, and whether secrets are protected by a real key or the dev one.
 * Every one of those has been a live misconfiguration, and each was previously discovered
 * only when a request failed.
 *
 * <p>Ordered last so the local host row seeded by {@link HostService} exists to be counted.
 * The MCP reconcile pass runs on its own executor, so its work is deliberately not waited on
 * — it reports itself as it completes.
 */
@Component
class StartupSummary {

  private static final Logger log = LoggerFactory.getLogger(StartupSummary.class);

  private final AppProperties props;
  private final HostService hosts;
  private final HostRepository hostRepository;
  private final String port;
  private final String datasourceUrl;
  private final String mcpStackDir;
  private final boolean secretKeySet;
  private final boolean rotationKeySet;

  StartupSummary(
      AppProperties props,
      HostService hosts,
      HostRepository hostRepository,
      @Value("${server.port:8080}") String port,
      @Value("${spring.datasource.url:}") String datasourceUrl,
      @Value("${mc.mcp-stack-dir:}") String mcpStackDir,
      @Value("${mc.secret-key:}") String secretKey,
      @Value("${mc.secret-key-previous:}") String previousSecretKey) {
    this.props = props;
    this.hosts = hosts;
    this.hostRepository = hostRepository;
    this.port = port;
    this.datasourceUrl = datasourceUrl;
    this.mcpStackDir = mcpStackDir;
    this.secretKeySet = secretKey != null && !secretKey.isBlank();
    this.rotationKeySet = previousSecretKey != null && !previousSecretKey.isBlank();
  }

  @Order(Ordered.LOWEST_PRECEDENCE)
  @EventListener(ApplicationReadyEvent.class)
  void onApplicationReady() {
    log.info("Mission Control {} listening on port {}", props.version(), port);
    log.info("  docker    {}{}", props.dockerSocket(), localDaemon());
    log.info("  database  {}", datasourceUrl.replaceFirst("^jdbc:sqlite:", ""));
    log.info("  mcp stack {}", mcpStackDir);
    log.info("  secrets   {}", secretKeyDescription());
    log.info("  hosts     {} registered, MCP reconcile {}",
        hostRepository.findAll().size(), props.startupReconcile() ? "on" : "off");
  }

  /**
   * Probes the local daemon rather than reading the cache, because at boot there is nothing
   * in the cache and an unmounted socket is exactly what this line exists to report. The
   * probe is the one already served to {@code /hosts}, so a failure is a WARN there and a
   * note here — never a startup failure: the dashboard is still usable for everything that
   * does not touch a container.
   */
  private String localDaemon() {
    try {
      DockerHostDto local = hosts.check(HostService.LOCAL_HOST_ID);
      return "connected".equals(local.status())
          ? " — " + local.engine() + " (api " + local.apiVersion() + ", " + local.latencyMs() + "ms)"
          : " — NOT REACHABLE: " + local.note();
    } catch (RuntimeException e) {
      return " — NOT REACHABLE: " + e.getMessage();
    }
  }

  private String secretKeyDescription() {
    if (!secretKeySet) return "built-in dev key — template secrets are NOT protected";
    return rotationKeySet ? "MC_SECRET_KEY (rotation fallback set)" : "MC_SECRET_KEY";
  }
}
