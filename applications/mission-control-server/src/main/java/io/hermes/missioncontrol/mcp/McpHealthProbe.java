package io.hermes.missioncontrol.mcp;

import io.hermes.missioncontrol.docker.DockerGateway;
import io.hermes.missioncontrol.hosts.HostService;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether an MCP server actually answers.
 *
 * <p>Split out of {@link McpRegistryService} because reachability is its own protocol
 * problem. A managed server is probed exactly the way an Agent reaches it — by Compose
 * service name, over the MCP network — since addressing it any other way would accept an
 * image whose MCP transport rejects that name as a Host header, which is the failure this
 * check exists to surface.
 */
@Component
class McpHealthProbe {

  private static final Logger log = LoggerFactory.getLogger(McpHealthProbe.class);

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);
  private static final Pattern CONTAINER_ID = Pattern.compile("/containers/([0-9a-f]{64})");
  private static final String MCP_INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,"
      + "\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
      + "\"capabilities\":{},\"clientInfo\":{\"name\":\"mission-control\",\"version\":\"1\"}}}";

  private final McpServerRepository repository;
  private final McpConfigStore configs;
  private final HostService hosts;
  private final DockerGateway docker;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(3))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();

  McpHealthProbe(
      McpServerRepository repository, McpConfigStore configs, HostService hosts,
      DockerGateway docker) {
    this.repository = repository;
    this.configs = configs;
    this.hosts = hosts;
    this.docker = docker;
  }

  /** Probes the record and writes the verdict onto it. The caller re-reads it. */
  void check(ServerRow row) {
    if ("stdio".equals(row.kind())) {
      throw new IllegalArgumentException("reachability checks do not apply to stdio MCP servers");
    }
    if ("managed".equals(row.kind())) checkManaged(row);
    else checkExternal(row);
  }

  private void checkExternal(ServerRow row) {
    String id = row.id();
    StoredConfig config = configs.read(row);
    long started = System.nanoTime();
    long checkedAt = System.currentTimeMillis();
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.url()))
          .timeout(Duration.ofSeconds(5)).method("HEAD", HttpRequest.BodyPublishers.noBody());
      for (Map.Entry<String, String> header : configs.materialize(config.headers()).entrySet()) {
        request.header(header.getKey(), header.getValue());
      }
      HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
      long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
      repository.updateCheck(id, "connected", null, checkedAt, latency);
      log.debug("external MCP {} responded with HTTP {}", id, response.statusCode());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      repository.updateCheck(id, "error", "reachability check interrupted", checkedAt, null);
    } catch (Exception e) {
      // a refused connection often carries no message at all; the managed path has
      // always fallen back to the exception type, and an operator needs the same
      // detail here rather than an "error" row with a blank reason
      repository.updateCheck(id, "error", rootMessage(e), checkedAt, null);
    }
  }

  private void checkManaged(ServerRow row) {
    String id = row.id();
    StoredConfig config = configs.read(row);
    long checkedAt = System.currentTimeMillis();
    if (!"running".equals(row.runtimeState())) {
      repository.updateCheck(id, "error", "server is not running", checkedAt, null);
      return;
    }
    String target;
    try {
      target = probeTarget(row, config);
    } catch (RuntimeException unreachable) {
      repository.updateCheck(id, "error", unreachable.getMessage(), checkedAt, null);
      return;
    }

    long started = System.nanoTime();
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
          .timeout(PROBE_TIMEOUT)
          .header("Accept", "application/json, text/event-stream");
      if ("sse".equals(config.transport())) {
        // The legacy SSE transport opens its stream on GET and answers POST with 405.
        request.GET();
      } else {
        request.header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MCP_INITIALIZE));
      }
      for (Map.Entry<String, String> header : configs.materialize(config.headers()).entrySet()) {
        request.header(header.getKey(), header.getValue());
      }
      HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = http
          .sendAsync(request.build(), HttpResponse.BodyHandlers.ofPublisher())
          .get(PROBE_TIMEOUT.toSeconds() + 1, TimeUnit.SECONDS);
      // Both transports answer with an event stream that stays open, so the probe reads the
      // response head and drops the subscription instead of waiting for a body that never ends.
      response.body().subscribe(new CancellingSubscriber());

      long latency = Math.max(0, (System.nanoTime() - started) / 1_000_000);
      String contentType = response.headers().firstValue("content-type").orElse("");
      String failure = probeFailure(response.statusCode(), contentType, config, target);
      if (failure == null) repository.updateCheck(id, "connected", null, checkedAt, latency);
      else repository.updateCheck(id, "error", failure, checkedAt, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      repository.updateCheck(id, "error", "reachability check interrupted", checkedAt, null);
    } catch (Exception e) {
      repository.updateCheck(id, "error", target + " — " + rootMessage(e), checkedAt, null);
    }
  }

  /** Null when the response is a healthy MCP endpoint, otherwise the operator-facing reason. */
  static String probeFailure(int status, String contentType, StoredConfig config, String target) {
    if (status == 421) {
      return "HTTP 421 from " + target + " — the image rejects the Compose service name as a Host "
          + "header. Its MCP transport accepts loopback hosts only, so no Agent can reach it.";
    }
    if (status == 404 || status == 405) {
      return "HTTP " + status + " from " + target + " — no MCP endpoint on that path.";
    }
    if (status / 100 != 2) return "HTTP " + status + " from " + target;
    if ("sse".equals(config.transport()) && !contentType.startsWith("text/event-stream")) {
      return target + " answered with '" + contentType + "' rather than an SSE stream.";
    }
    return null;
  }

  private String probeTarget(ServerRow row, StoredConfig config) {
    if (HostService.LOCAL_HOST_ID.equals(row.hostId())) {
      attachMcpNetwork(row.hostId());
      return McpServerDtoMapper.connectionUrl(row, config);
    }
    String crossHost = config.crossHostUrl();
    if (crossHost == null || crossHost.isBlank()) {
      throw new IllegalStateException(
          "a managed server on a remote Docker host can only be checked through a cross-host URL");
    }
    return crossHost;
  }

  /** Joins the MCP network so service names resolve. Idempotent, and cheap once attached. */
  private void attachMcpNetwork(String hostId) {
    String container = ownNetworkContainerId();
    if (container == null) {
      throw new IllegalStateException(
          "cannot reach the MCP network: Mission Control is not running inside a container");
    }
    docker.connectNetwork(hosts.ref(hostId), container, ComposeStackRenderer.NETWORK);
  }

  /**
   * The container owning this process' network namespace. Docker bind-mounts the network files
   * (/etc/hosts, /etc/resolv.conf) out of that container's directory, so its id is the one that
   * shows up in mountinfo — this container normally, and the namespace owner when the deployment
   * shares one, as the Tailscale compose file does with {@code network_mode: service:tailscale}.
   * That is exactly the container a network has to be attached to for this process to use it.
   *
   * <p>Package-private and non-static so a test can substitute it: the answer comes from this
   * process' own {@code /proc/self/mountinfo}, so what {@link #attachMcpNetwork} does with it is
   * otherwise decided by whether the test run itself happens to be containerized.
   */
  String ownNetworkContainerId() {
    try {
      return containerIdFrom(Files.readAllLines(Path.of("/proc/self/mountinfo")));
    } catch (Exception notContainerized) {
      return null;
    }
  }

  /**
   * The container id in the first mountinfo line that carries one, or null when this
   * process is not in a container. Split out from the file read so the rule that decides
   * <em>which</em> line wins is reachable without a container to run inside.
   */
  static String containerIdFrom(List<String> mountinfoLines) {
    for (String line : mountinfoLines) {
      Matcher matcher = CONTAINER_ID.matcher(line);
      if (matcher.find()) return matcher.group(1);
    }
    return null;
  }

  /**
   * The reason an operator sees for a failed probe: the first message in the cause chain, or
   * the exception's type when nothing in it carries one — a refused connection frequently
   * does not. Package-private and non-static so the fallback is reachable without a network
   * failure to provoke; both probe paths record through it.
   */
  static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  /** Reads the response head and releases the connection without draining the stream. */
  private static final class CancellingSubscriber implements Flow.Subscriber<List<ByteBuffer>> {
    @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
    @Override public void onNext(List<ByteBuffer> item) { }
    @Override public void onError(Throwable throwable) { }
    @Override public void onComplete() { }
  }
}
