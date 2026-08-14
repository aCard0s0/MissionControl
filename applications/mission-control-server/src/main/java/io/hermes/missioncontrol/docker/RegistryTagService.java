package io.hermes.missioncontrol.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Published tags for the Hermes image, read from Docker Hub.
 *
 * <p>The daemon only knows what has been pulled, so without this a newly
 * released version is invisible until someone pulls it by hand. Results are
 * cached per repository: tag lists change on the order of days, and callers
 * poll on the order of minutes.
 *
 * <p>This never throws — the local image list is always a usable answer, so a
 * registry outage degrades the response rather than failing the request.
 */
@Service
public class RegistryTagService {

  private static final Logger log = LoggerFactory.getLogger(RegistryTagService.class);

  private static final String HUB = "https://hub.docker.com/v2/repositories/";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration PAGE_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration TOTAL_BUDGET = Duration.ofSeconds(8);
  private static final long OK_TTL_MS = 600_000;      // 10 min
  private static final long ERROR_TTL_MS = 60_000;    // negative cache, so an offline host answers fast
  private static final int MAX_PAGES = 5;

  public static final String OK = "ok";
  public static final String CACHED = "cached";
  public static final String UNAVAILABLE = "unavailable";
  public static final String UNSUPPORTED = "unsupported";
  public static final String DISABLED = "disabled";

  /** @param checkedAt epoch ms of the reading, null when no lookup was attempted */
  public record RemoteTags(List<ImageTagDto> tags, String status, String detail, Long checkedAt) {
  }

  private record Cached(List<ImageTagDto> tags, String detail, long at, boolean ok) {
  }

  private final ObjectMapper json;
  private final HttpClient http = HttpClient.newBuilder()
      .connectTimeout(CONNECT_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build();
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();
  private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

  private final boolean enabled;

  public RegistryTagService(ObjectMapper json, @Value("${mc.registry-tags:true}") boolean enabled) {
    this.json = json;
    this.enabled = enabled;
  }

  /** Published tags for {@code repository}. Degrades to an empty list with a status. */
  public RemoteTags tags(String repository) {
    if (!enabled) {
      return new RemoteTags(List.of(), DISABLED, "registry tag lookup is disabled", null);
    }
    String path = ImageRef.dockerHubPath(repository);
    if (path == null) {
      return new RemoteTags(
          List.of(), UNSUPPORTED, "remote tag listing supports Docker Hub only", null);
    }

    Cached hit = cache.get(path);
    long now = System.currentTimeMillis();
    if (hit != null && now - hit.at() < (hit.ok() ? OK_TTL_MS : ERROR_TTL_MS)) {
      return hit.ok()
          ? new RemoteTags(hit.tags(), OK, null, hit.at())
          : new RemoteTags(hit.tags(), hit.tags().isEmpty() ? UNAVAILABLE : CACHED,
              hit.detail(), hit.at());
    }

    // one fetch per repository at a time — concurrent callers read the cache
    // rather than stacking identical requests against the registry
    ReentrantLock lock = locks.computeIfAbsent(path, key -> new ReentrantLock());
    if (!lock.tryLock()) {
      return hit != null
          ? new RemoteTags(hit.tags(), hit.ok() ? OK : CACHED, hit.detail(), hit.at())
          : new RemoteTags(List.of(), UNAVAILABLE, "registry lookup in progress", null);
    }
    try {
      List<ImageTagDto> fetched = fetchAll(path);
      cache.put(path, new Cached(fetched, null, now, true));
      return new RemoteTags(fetched, OK, null, now);
    } catch (Exception e) {
      String detail = brief(e);
      log.warn("registry tag lookup failed for {}: {}", path, detail);
      // keep a previous good list rather than dropping to nothing on a blip
      List<ImageTagDto> stale = hit != null && !hit.tags().isEmpty() ? hit.tags() : List.of();
      cache.put(path, new Cached(stale, detail, now, false));
      return new RemoteTags(stale, stale.isEmpty() ? UNAVAILABLE : CACHED, detail, now);
    } finally {
      lock.unlock();
    }
  }

  private List<ImageTagDto> fetchAll(String path) throws Exception {
    List<ImageTagDto> all = new ArrayList<>();
    String url = HUB + path + "/tags?page_size=100";
    long deadline = System.nanoTime() + TOTAL_BUDGET.toNanos();
    for (int page = 0; page < MAX_PAGES && url != null; page++) {
      if (System.nanoTime() > deadline) {
        log.warn("registry tag lookup for {} stopped after {} page(s) — time budget spent", path, page);
        break;
      }
      HttpResponse<String> response = http.send(
          HttpRequest.newBuilder(URI.create(url)).timeout(PAGE_TIMEOUT).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("registry responded " + response.statusCode());
      }
      JsonNode body = json.readTree(response.body());
      all.addAll(parseHubPage(body));
      url = nextHubPage(body);
    }
    return List.copyOf(all);
  }

  /** Reads one Docker Hub tag page. Inactive tags are dropped — they cannot be pulled. */
  static List<ImageTagDto> parseHubPage(JsonNode page) {
    JsonNode results = page == null ? null : page.get("results");
    if (results == null || !results.isArray()) return List.of();
    List<ImageTagDto> tags = new ArrayList<>();
    for (JsonNode entry : results) {
      String name = text(entry, "name");
      if (name == null || name.isBlank()) continue;
      String status = text(entry, "tag_status");
      if (status != null && !"active".equals(status)) continue;
      tags.add(new ImageTagDto(
          name, false, true,
          epochMs(text(entry, "last_updated")),
          entry.hasNonNull("full_size") ? entry.get("full_size").asLong() : null,
          text(entry, "digest")));
    }
    return tags;
  }

  /**
   * The next page URL, or null. A 'next' pointing anywhere but Docker Hub is
   * discarded so a surprising response cannot steer requests at another host.
   */
  static String nextHubPage(JsonNode page) {
    String next = page == null ? null : text(page, "next");
    return next != null && next.startsWith(HUB) ? next : null;
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private static Long epochMs(String iso) {
    if (iso == null || iso.isBlank()) return null;
    try {
      return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
    } catch (RuntimeException unparseable) {
      return null;
    }
  }

  private static String brief(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) return e.getClass().getSimpleName();
    return message.length() > 200 ? message.substring(0, 200) + "…" : message;
  }
}
