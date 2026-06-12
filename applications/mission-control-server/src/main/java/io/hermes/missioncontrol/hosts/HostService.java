package io.hermes.missioncontrol.hosts;

import io.hermes.missioncontrol.AppProperties;
import io.hermes.missioncontrol.docker.DockerGateway;
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
import org.springframework.stereotype.Service;

@Service
public class HostService {

  private static final Logger log = LoggerFactory.getLogger(HostService.class);

  public static final String LOCAL_HOST_ID = "dh-local";
  private static final long PROBE_TTL_MS = 10_000;

  private record Probe(String status, String engine, String apiVersion, Long latencyMs, String note, long at) {}

  private final HostRepository repository;
  private final DockerGateway docker;
  private final AppProperties props;
  private final Map<String, Probe> probeCache = new ConcurrentHashMap<>();

  public HostService(HostRepository repository, DockerGateway docker, AppProperties props) {
    this.repository = repository;
    this.docker = docker;
    this.props = props;
  }

  /** The local daemon row always exists — seeded from MC_DOCKER_SOCKET. */
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
  }

  public String urlOf(String hostId) {
    return require(hostId).url();
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
      DockerGateway.DaemonInfo info = docker.ping(row.url());
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
