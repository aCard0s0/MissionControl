package io.hermes.missioncontrol.docker;

import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Statistics;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Live resource samples, read from one long-lived stats stream per container.
 *
 * <p>The daemon's {@code /stats?stream=false} does not answer from a counter it already has:
 * CPU is a delta, so it takes one sample, waits, takes a second, and only then replies.
 * Measured against this project's own daemon that is 1.0–2.0 seconds per call — a request
 * that spends nearly all of its life blocked. The fleet view asks for one per running
 * container every three seconds, and {@code mapPool} runs six at a time, so at seven running
 * containers the fan-out stops fitting inside its own period and ticks are silently dropped.
 *
 * <p>A streamed {@code /stats} has the same cost once and then pushes a sample every second
 * for as long as it is held open. Reading the newest one costs nothing, which makes a request
 * for every container at once cheap enough to answer from memory, and takes the number of
 * daemon connections off both the container count per tick and the number of open dashboards.
 *
 * <p>Streams are opened on demand and reaped {@link #IDLE_TTL} after the last request for
 * that container. That is deliberate: a dashboard nobody has open must cost nothing, which is
 * the same reason the browser stops polling on {@code document.hidden}. An always-on sampler
 * would hold a connection per container for the life of the process and hand the daemon a
 * sample per second forever, which is worse than what it replaces.
 */
@Component
public class ContainerStatsStreams {

  private static final Logger log = LoggerFactory.getLogger(ContainerStatsStreams.class);

  /** How long a stream outlives the last request for its container. */
  private static final Duration IDLE_TTL = Duration.ofSeconds(30);

  /** How often idle and finished streams are swept. */
  private static final Duration SWEEP = Duration.ofSeconds(10);

  /**
   * How old the newest sample may be before it is withheld.
   *
   * <p>A stream delivers roughly one a second, so anything this old means it has wedged or is
   * still opening. Reporting it would draw a sparkline out of a number from another minute.
   */
  private static final Duration STALE_AFTER = Duration.ofSeconds(10);

  /**
   * Ceiling on concurrently held streams.
   *
   * <p>Each is a daemon connection and a reader thread, and the request names the containers
   * it wants — so without a bound, one caller asking about a large daemon decides how many of
   * both this process holds. Beyond it a container simply reports no sample.
   */
  private static final int MAX_STREAMS = 100;

  private final DockerClients clients;
  private final Map<String, Sampler> samplers = new ConcurrentHashMap<>();

  private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "stats-stream-reaper");
    t.setDaemon(true);
    return t;
  });

  public ContainerStatsStreams(DockerClients clients) {
    this.clients = clients;
  }

  @PostConstruct
  void startReaper() {
    reaper.scheduleAtFixedRate(
        this::sweep, SWEEP.toMillis(), SWEEP.toMillis(), TimeUnit.MILLISECONDS);
  }

  @PreDestroy
  void stopReaper() {
    reaper.shutdownNow();
    // the reader threads are the transport's, not ours — closing the callbacks is what ends them
    for (String key : List.copyOf(samplers.keySet())) {
      Sampler sampler = samplers.remove(key);
      if (sampler != null) sampler.close();
    }
  }

  /**
   * The newest sample for each named container, opening a stream for any that lacks one.
   *
   * <p>A container with no usable sample yet is absent from the result rather than present
   * with a zero: the first read after a stream opens lands before the daemon has sent
   * anything, and a card that already shows a figure should keep it rather than blink to 0%.
   */
  public Map<String, StatsDto> samples(DockerHostRef host, List<String> containerIds) {
    return samples(host, containerIds, System.currentTimeMillis());
  }

  /** Package-private clock seam: idle expiry and staleness are both times, not events. */
  Map<String, StatsDto> samples(DockerHostRef host, List<String> containerIds, long now) {
    Map<String, StatsDto> result = new LinkedHashMap<>();
    for (String containerId : containerIds) {
      Sampler sampler = ensure(host.url(), containerId, now);
      if (sampler == null) continue;
      StatsDto latest = sampler.latest;
      if (latest != null && now - latest.sampledAt() <= STALE_AFTER.toMillis()) {
        result.put(containerId, latest);
      }
    }
    return result;
  }

  /** The live stream for a container, opened or replaced as needed; null if it cannot be had. */
  private Sampler ensure(String url, String containerId, long now) {
    try {
      return samplers.compute(key(url, containerId), (key, existing) -> {
        if (existing != null && !existing.done) {
          existing.touchedAt = now;
          return existing;
        }
        // a finished stream is a container that stopped, was replaced, or a daemon that
        // dropped the connection — reopening is how a restarted container starts reporting again
        if (existing != null) existing.close();
        if (existing == null && samplers.size() >= MAX_STREAMS) return null;
        return open(url, containerId, now);
      });
    } catch (RuntimeException unavailable) {
      // a container that stopped between the listing and this call, or a daemon that is gone;
      // both are already reported by the endpoints that exist to report them
      log.debug("stats stream for {} unavailable: {}", containerId, unavailable.toString());
      return null;
    }
  }

  private Sampler open(String url, String containerId, long now) {
    Sampler sampler = new Sampler(now);
    ResultCallback.Adapter<Statistics> callback = new ResultCallback.Adapter<>() {
      @Override
      public void onNext(Statistics stats) {
        sampler.latest = ContainerStatsReader.toStats(stats);
      }

      @Override
      public void onError(Throwable throwable) {
        sampler.done = true;
        super.onError(throwable);
      }

      @Override
      public void onComplete() {
        sampler.done = true;
        super.onComplete();
      }
    };
    sampler.callback = callback;
    // streamingForUrl, not forUrl: this response is never meant to end, and the unary client
    // installs its responseTimeout as the socket timeout for the whole exchange
    clients.streamingForUrl(url).statsCmd(containerId).withNoStream(false).exec(callback);
    return sampler;
  }

  /** Closes streams nobody has asked about lately, and any the daemon has already ended. */
  private void sweep() {
    sweep(System.currentTimeMillis());
  }

  /** Package-private for the same reason as {@link #samples(DockerHostRef, List, long)}. */
  void sweep(long now) {
    long deadline = now - IDLE_TTL.toMillis();
    for (Map.Entry<String, Sampler> entry : Map.copyOf(samplers).entrySet()) {
      Sampler sampler = entry.getValue();
      if (!sampler.done && sampler.touchedAt >= deadline) continue;
      if (samplers.remove(entry.getKey(), sampler)) sampler.close();
    }
  }

  /** How many streams are open — the bound {@link #MAX_STREAMS} and the reaper both act on. */
  int openStreamCount() {
    return samplers.size();
  }

  private static String key(String url, String containerId) {
    return url + "/" + containerId;
  }

  /** One container's stream, and the newest thing it has delivered. */
  private static final class Sampler {

    private volatile ResultCallback.Adapter<Statistics> callback;
    private volatile StatsDto latest;
    private volatile long touchedAt;
    private volatile boolean done;

    private Sampler(long touchedAt) {
      this.touchedAt = touchedAt;
    }

    private void close() {
      done = true;
      ResultCallback.Adapter<Statistics> open = callback;
      if (open == null) return;
      try {
        open.close();
      } catch (Exception e) {
        // the connection is being dropped either way; a failure here strands nothing further
        log.debug("closing a stats stream failed: {}", e.toString());
      }
    }
  }
}
