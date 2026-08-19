package io.hermes.missioncontrol.hosts;

import io.hermes.missioncontrol.config.AppProperties;
import io.hermes.missioncontrol.docker.DaemonInfo;
import io.hermes.missioncontrol.docker.DockerClients;
import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.errors.UpstreamUnavailableException;
import io.hermes.missioncontrol.hosts.HostRepository.HostRow;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
public class HostService {

  private static final Logger log = LoggerFactory.getLogger(HostService.class);

  public static final String LOCAL_HOST_ID = "dh-local";
  private static final long PROBE_TTL_MS = 10_000;

  private record Probe(String status, String engine, String apiVersion, Long latencyMs, String note, long at) {}

  private final HostRepository repository;
  private final DockerGateway docker;
  private final DockerClients clients;
  private final AppProperties props;
  private final Map<String, Probe> probeCache = new ConcurrentHashMap<>();

  public HostService(
      HostRepository repository, DockerGateway docker, DockerClients clients, AppProperties props) {
    this.repository = repository;
    this.docker = docker;
    this.clients = clients;
    this.props = props;
  }

  /**
   * The local daemon row always exists — seeded from MC_DOCKER_SOCKET.
   *
   * <p>Ordered ahead of every other {@code ApplicationReadyEvent} listener because they may
   * assume the row: the MCP catalog's startup reconciler seeds default servers onto this host
   * and cannot resolve it otherwise. It used to call this method itself to be sure, since two
   * unordered listeners have no defined order between them — this annotation is what replaced
   * that, and it must stay for as long as anything downstream reads a host row at startup.
   *
   * <p>Not {@code @PostConstruct}: the row is written through the schema this application
   * populates during context refresh, which a bean's own initialization is not guaranteed to
   * follow.
   */
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ApplicationReadyEvent.class)
  public void seedLocalHost() {
    if (repository.findById(LOCAL_HOST_ID).isEmpty()) {
      repository.insert(new HostRow(LOCAL_HOST_ID, "localhost", props.dockerSocket(), "local"));
    }
  }

  public List<DockerHostDto> list() {
    return repository.findAll().stream().map(row -> toDto(row, probe(row, false))).toList();
  }

  public DockerHostDto check(String id) {
    HostRow row = require(id);
    return toDto(row, probe(row, true));
  }

  /**
   * The host, only if its daemon answered. Every endpoint that is about to talk to a
   * container resolves through here rather than {@link #ref}, so that a dead daemon is
   * reported as a 503 instead of surfacing later as an obscure Docker error.
   *
   * <p>Reads the {@link #PROBE_TTL_MS} probe cache rather than forcing a ping, which
   * {@link #check} still does. Forcing here would put a daemon round trip in front of
   * every request on the polling path — {@code /stats} refreshes every 3s and
   * {@code /logs} every 5s — and a reachability check from moments ago is the same
   * evidence this class already serves to {@link #list}. The one thing a stale
   * {@code connected} verdict costs is that the failure is reported by whatever the
   * request then attempts, which for an exec is {@code DockerExecService} translating the
   * transport error into the same 503 — never a 500.
   */
  public DockerHostRef requireConnected(String id) {
    HostRow row = require(id);
    if (!"connected".equals(probe(row, false).status())) {
      throw new UpstreamUnavailableException("docker host not connected");
    }
    return new DockerHostRef(row.id(), row.url());
  }

  /**
   * The host without probing it — for work that is not answering a request and so has no
   * caller to report a 503 to: the MCP compose lifecycle running on its own executor, a
   * log tail for a record whose host is resolved per row, an existence check.
   *
   * <p>Still fails on an unknown id. Anything serving an HTTP request wants
   * {@link #requireConnected} instead.
   */
  public DockerHostRef ref(String hostId) {
    HostRow row = require(hostId);
    return new DockerHostRef(row.id(), row.url());
  }

  public DockerHostDto add(String name, String url) {
    if (!url.matches("^tcp://.+:\\d+$")) {
      throw new IllegalArgumentException("remote host url must look like tcp://host:port");
    }
    if (repository.urlExists(url)) {
      throw new IllegalArgumentException("a host with this url already exists");
    }
    HostRow row = new HostRow("dh-" + UUID.randomUUID().toString().substring(0, 8), name, url, "remote");
    repository.insert(row);
    return toDto(row, probe(row, true));
  }

  public void delete(String id) {
    HostRow row = require(id);
    if ("local".equals(row.kind())) {
      throw new IllegalArgumentException("the local socket host cannot be removed");
    }
    repository.delete(id);
    probeCache.remove(id);
    // the url is read from the row above, because it is no longer resolvable from the id
    clients.release(row.url());
  }

  public boolean isLocalDaemonConnected() {
    return repository.findById(LOCAL_HOST_ID)
        .map(row -> "connected".equals(probe(row, false).status()))
        .orElse(false);
  }

  private HostRow require(String id) {
    return repository.findById(id)
        .orElseThrow(() -> new NoSuchElementException("unknown docker host: " + id));
  }

  private Probe probe(HostRow row, boolean force) {
    Probe cached = probeCache.get(row.id());
    if (!force && cached != null && System.currentTimeMillis() - cached.at() < PROBE_TTL_MS) {
      return cached;
    }
    Probe fresh;
    try {
      DaemonInfo info = docker.ping(row.url());
      fresh = new Probe("connected", info.engine(), info.apiVersion(), info.latencyMs(), null,
          System.currentTimeMillis());
    } catch (Exception e) {
      log.warn("probe of {} ({}) failed: {}", row.id(), row.url(), e.toString());
      String note = "local".equals(row.kind())
          ? "docker socket not reachable — is /var/run/docker.sock mounted into the container?"
          : "daemon not reachable — check the address, firewall, and that the API is exposed";
      fresh = new Probe("error", null, null, null, note, System.currentTimeMillis());
    }
    probeCache.put(row.id(), fresh);
    return fresh;
  }

  private static DockerHostDto toDto(HostRow row, Probe probe) {
    return new DockerHostDto(row.id(), row.name(), row.url(), row.kind(),
        probe.status(), probe.engine(), probe.apiVersion(), probe.latencyMs(), probe.note());
  }
}
