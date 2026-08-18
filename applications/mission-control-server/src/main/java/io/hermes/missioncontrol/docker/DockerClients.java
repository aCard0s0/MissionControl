package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

  /**
   * Ceiling for a command that answers in one shot. Worth keeping: a wedged daemon then
   * fails a request instead of pinning the request thread indefinitely.
   */
  private static final Duration UNARY_RESPONSE_TIMEOUT = Duration.ofSeconds(20);

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

  private final Map<String, DockerClient> unary = new ConcurrentHashMap<>();
  private final Map<String, DockerClient> streaming = new ConcurrentHashMap<>();

  /** For request/response commands — inspect, list, create, start, stop, rename, remove. */
  public DockerClient forUrl(String url) {
    return unary.computeIfAbsent(url, u -> build(u, UNARY_RESPONSE_TIMEOUT));
  }

  /**
   * For endpoints that stream or long-poll: exec attach, {@code /wait}, log tails, image
   * pulls, stats and the web terminal. These carry no socket timeout, so the caller's own
   * bound — {@code awaitCompletion(timeout)}, an idle-session reaper — is the real limit.
   */
  public DockerClient streamingForUrl(String url) {
    return streaming.computeIfAbsent(url, u -> build(u, null));
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
