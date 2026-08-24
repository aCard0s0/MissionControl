package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cached Docker clients per daemon url (unix:// or tcp://), in two flavours.
 *
 * <p>The split exists because {@code responseTimeout} is not a time-to-first-byte bound.
 * The zerodep transport deliberately disables the socket timeout
 * ({@code SocketConfig.setSoTimeout(ZERO)}, docker-java PR #1590) so that streamed
 * responses never expire, and Apache HttpClient 5 then re-installs whatever
 * {@code responseTimeout} is configured as the socket timeout for the whole exchange
 * ({@code InternalExecRuntime}: {@code endpoint.setSocketTimeout(responseTimeout)}).
 *
 * <p>So a single client with a response timeout caps how long ANY response body may stay
 * quiet. Endpoints that are silent by nature — {@code exec} attach while a command runs,
 * {@code /wait} until a container exits, an idle terminal, a slow pull — die at that
 * ceiling regardless of the budget the caller asked for.
 *
 * <p>Known limitation: pooled connections are neither validated nor evicted while idle
 * (the transport sets {@code validateAfterInactivity} to -1 and {@code ZerodepDockerHttpClient.Builder}
 * exposes no TTL or eviction knob), so against a remote {@code tcp://} daemon the first call
 * after a firewall or NAT has silently reaped a connection can fail and need a retry.
 * Closing that needs a different transport, not a different setting here.
 */
@Component
public class DockerClients {

  private static final Logger log = LoggerFactory.getLogger(DockerClients.class);

  /**
   * Ceiling for a command that answers in one shot. Worth keeping: a wedged daemon then
   * fails a request instead of pinning the request thread indefinitely.
   */
  private static final Duration UNARY_RESPONSE_TIMEOUT = Duration.ofSeconds(20);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /**
   * How a client is built, as a seam.
   *
   * <p>Only {@link #release} needs it. Building a client talks to no daemon, so the caching
   * tests drive the real thing — but a real {@code close()} is silent, and the one thing
   * worth asserting about release is that the pool was actually closed and not merely
   * dropped from the map.
   */
  interface ClientFactory {
    DockerClient create(String url, Duration responseTimeout);
  }

  private final ClientFactory factory;
  private final Map<String, DockerClient> unary = new ConcurrentHashMap<>();
  private final Map<String, DockerClient> streaming = new ConcurrentHashMap<>();

  // Spring instantiates through the no-arg constructor: with no @Autowired anywhere it takes
  // the default one, which here is the production one. (RegistryTagService needs the opposite
  // annotation because it has no no-arg constructor to fall back to.)
  public DockerClients() {
    this(DockerClients::build);
  }

  DockerClients(ClientFactory factory) {
    this.factory = factory;
  }

  /**
   * For request/response commands — inspect, list, create, start, stop, rename, remove, and
   * the one-shot {@code stats?stream=false} in {@link ContainerStatsReader}, which despite
   * the endpoint's name does end on its own.
   */
  public DockerClient forUrl(String url) {
    return unary.computeIfAbsent(url, u -> factory.create(u, UNARY_RESPONSE_TIMEOUT));
  }

  /**
   * For endpoints that stream or long-poll: exec attach, {@code /wait}, log tails, image
   * pulls, the web terminal, and the held-open {@code stats} streams in
   * {@link ContainerStatsStreams}. These carry no socket timeout, so the caller's own bound —
   * {@code awaitCompletion(timeout)}, an idle-session reaper — is the real limit.
   *
   * <p>The split is by how the response behaves, not by which endpoint it came from: the two
   * stats readers sit on either side of it for exactly that reason.
   */
  public DockerClient streamingForUrl(String url) {
    return streaming.computeIfAbsent(url, u -> factory.create(u, null));
  }

  /**
   * Drops both clients for a daemon url and closes their connection pools.
   *
   * <p>Called when a host is removed. Without it the two clients — each holding pooled
   * sockets, and the threads Apache HttpClient runs them on — stay for the life of the
   * process, keyed by a url nothing references any more. Idempotent, so a repeated or
   * unknown url is a no-op.
   *
   * <p>A close failure is logged and the release continues. The host row is already gone by
   * then, so there is nothing useful to abort: refusing to finish would only strand the
   * other client.
   *
   * <p>Known narrow race: a request that resolved this url just before the row was deleted
   * can rebuild a client afterwards, re-adding one entry. It cannot grow beyond the requests
   * already in flight at that moment, because the row is deleted first and every later
   * request fails on the unknown host id before it reaches a url.
   */
  public void release(String url) {
    closeQuietly(url, unary.remove(url));
    closeQuietly(url, streaming.remove(url));
  }

  private static void closeQuietly(String url, DockerClient client) {
    if (client == null) {
      return;
    }
    try {
      client.close();
    } catch (Exception e) {
      log.warn("closing the docker client for {} failed: {}", url, e.toString());
    }
  }

  /**
   * How many clients are cached across both flavours.
   *
   * <p>Exists for the test that pins the bound: {@link #forUrl} hands back the same instance
   * whether or not the map is leaking, so nothing else distinguishes a released client from a
   * retained one.
   */
  int cachedClientCount() {
    return unary.size() + streaming.size();
  }

  private static DockerClient build(String url, Duration responseTimeout) {
    DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
        .withDockerHost(url)
        .build();
    ZerodepDockerHttpClient.Builder httpClient = new ZerodepDockerHttpClient.Builder()
        .dockerHost(config.getDockerHost())
        .sslConfig(config.getSSLConfig())
        .connectionTimeout(CONNECT_TIMEOUT);
    // leaving responseTimeout unset is what keeps the transport's socket timeout disabled
    if (responseTimeout != null) httpClient.responseTimeout(responseTimeout);
    return DockerClientImpl.getInstance(config, httpClient.build());
  }
}
