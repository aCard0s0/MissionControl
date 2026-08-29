package io.hermes.missioncontrol.skills;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Whether a library skill's source repository has moved on.
 *
 * <p>On demand and cached, never on a timer: the answer changes on the order of days, the
 * question is asked by a person clicking, and unauthenticated GitHub allows sixty requests
 * an hour per address. Like {@code docker.RegistryTagService} this never throws — the row is
 * a usable answer without it, so a GitHub outage degrades the response rather than failing
 * the request.
 *
 * <p><b>The stored URL is never fetched.</b> It is parsed to an owner and repository, both
 * validated, and the API URL is built here from those two words. An operator-typed URL that
 * reached {@code HttpClient} would be a request this server makes to wherever they said,
 * which is a different and much worse feature than checking a version.
 */
@Service
public class UpstreamCheck {

  private static final Logger log = LoggerFactory.getLogger(UpstreamCheck.class);

  /** Versions match. */
  public static final String CURRENT = "current";
  /** Upstream reports something else. Deliberately not "newer": see {@link #compare}. */
  public static final String UPDATE = "update";
  /** Upstream answered, but the row records no version to compare it against. */
  public static final String UNKNOWN = "unknown";
  /** No repository link, or one this cannot resolve to a GitHub repository. */
  public static final String UNSUPPORTED = "unsupported";
  /** The lookup itself failed. */
  public static final String UNAVAILABLE = "unavailable";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
  private static final long OK_TTL_MS = 600_000;     // 10 min — releases move on days
  private static final long ERROR_TTL_MS = 60_000;   // negative cache, so an outage answers fast

  /** GitHub's own charset for an owner or repository name. */
  private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /**
   * @param latest what upstream calls its newest release, or null when nothing was read
   * @param checkedAt epoch ms of the reading, null when no lookup was attempted
   */
  public record Upstream(String status, String latest, String detail, Long checkedAt) {
  }

  /**
   * The single outbound call this service makes, as a seam.
   *
   * <p>Everything worth testing — the TTLs, the negative cache, the releases-to-tags
   * fallback, and that a hostile URL never reaches it at all — is only reachable by driving
   * responses. The same reason {@code RegistryTagService.PageFetcher} exists.
   */
  interface Fetcher {
    /** @return the response body, or throws; {@code null} for a 404, which is not an error */
    String get(String url) throws Exception;
  }

  private record Cached(Upstream upstream, long at) {
  }

  private final ObjectMapper json;
  private final Fetcher fetcher;
  private final LongSupplier clock;

  /*
   * Keyed by owner/repo, which is operator-supplied — so unlike RegistryTagService's map
   * this one could grow without bound, and its comment says as much. Expired entries are
   * swept on write instead, the shape HermesProfileMcp uses: O(size) per write, affordable
   * because a write is one person clicking check on one skill.
   */
  private final Map<String, Cached> cache = new ConcurrentHashMap<>();

  // @Autowired is load-bearing: the test seam below makes this a multi-constructor bean, and
  // Spring then looks for a no-arg constructor instead of choosing one.
  @Autowired
  public UpstreamCheck(ObjectMapper json) {
    this(json, httpFetcher(), System::currentTimeMillis);
  }

  UpstreamCheck(ObjectMapper json, Fetcher fetcher, LongSupplier clock) {
    this.json = json;
    this.fetcher = fetcher;
    this.clock = clock;
  }

  private static Fetcher httpFetcher() {
    HttpClient http = HttpClient.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    return url -> {
      HttpResponse<String> response = http.send(
          HttpRequest.newBuilder(URI.create(url))
              .timeout(READ_TIMEOUT)
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build(),
          HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404) {
        return null;   // a repository with no releases is a fact, not a failure
      }
      if (response.statusCode() != 200) {
        throw new IllegalStateException("github responded " + response.statusCode());
      }
      return response.body();
    };
  }

  /** What upstream has, against what this row records. Never throws. */
  public Upstream check(String repoUrl, String version) {
    String repo = githubRepo(repoUrl);
    if (repo == null) {
      return new Upstream(UNSUPPORTED,
          null, "only github.com repositories can be checked", null);
    }

    long now = clock.getAsLong();
    Cached hit = cache.get(repo);
    if (hit != null && now - hit.at() < ttl(hit.upstream())) {
      // the comparison is re-run rather than cached with the reading: the row's own version
      // can change without upstream moving, and a stale "update available" on a skill the
      // operator has since bumped is exactly the wrong answer
      return compare(hit.upstream(), version);
    }

    Upstream reading = read(repo, now);
    cache.values().removeIf(cached -> now - cached.at() >= ttl(cached.upstream()));
    cache.put(repo, new Cached(reading, now));
    return compare(reading, version);
  }

  /**
   * How many readings are cached right now.
   *
   * <p>Exists for the test that pins the bound. Eviction is otherwise unobservable from
   * outside: a check reports the same answer whether the entry was swept, expired on read,
   * or never taken, so nothing else can tell a bounded map from one that only ever grows.
   */
  int cachedCount() {
    return cache.size();
  }

  private static long ttl(Upstream upstream) {
    return UNAVAILABLE.equals(upstream.status()) ? ERROR_TTL_MS : OK_TTL_MS;
  }

  /** One release lookup, falling back to tags for a repository that cuts no releases. */
  private Upstream read(String repo, long now) {
    try {
      String latest = latestRelease(repo);
      if (latest == null) {
        latest = newestTag(repo);
      }
      if (latest == null || latest.isBlank()) {
        return new Upstream(UNSUPPORTED, null, "repository publishes no releases or tags", now);
      }
      return new Upstream(UNKNOWN, latest, null, now);
    } catch (Exception e) {
      log.debug("upstream check failed for {}: {}", repo, e.getMessage());
      return new Upstream(UNAVAILABLE, null, "could not reach github", now);
    }
  }

  private String latestRelease(String repo) throws Exception {
    String body = fetcher.get("https://api.github.com/repos/" + repo + "/releases/latest");
    return body == null ? null : text(json.readTree(body).path("tag_name"));
  }

  private String newestTag(String repo) throws Exception {
    String body = fetcher.get("https://api.github.com/repos/" + repo + "/tags?per_page=1");
    if (body == null) {
      return null;
    }
    JsonNode tags = json.readTree(body);
    return tags.isArray() && !tags.isEmpty() ? text(tags.get(0).path("name")) : null;
  }

  private static String text(JsonNode node) {
    return node.isTextual() ? node.asText() : null;
  }

  /**
   * The reading, with a status that accounts for what this row records.
   *
   * <p>An exact comparison after dropping one leading {@code v}, and never an ordering.
   * {@code version} is free text an operator typed, so deciding that {@code 1.10} is behind
   * {@code 1.9} — or that either is behind {@code 2024-06-release} — would be a guess
   * presented as a fact. Differing is reported as differing, with both values, and the
   * person reading decides.
   */
  private static Upstream compare(Upstream reading, String version) {
    if (reading.latest() == null) {
      return reading;
    }
    if (version == null || version.isBlank()) {
      return new Upstream(UNKNOWN, reading.latest(),
          "no version recorded for this skill", reading.checkedAt());
    }
    boolean same = normalize(version).equals(normalize(reading.latest()));
    return new Upstream(
        same ? CURRENT : UPDATE,
        reading.latest(),
        same ? null : "upstream is at " + reading.latest() + ", this row records " + version,
        reading.checkedAt());
  }

  private static String normalize(String version) {
    String trimmed = version.trim().toLowerCase(Locale.ROOT);
    return trimmed.startsWith("v") ? trimmed.substring(1) : trimmed;
  }

  /**
   * {@code owner/repo} from a GitHub URL, or null for anything else.
   *
   * <p>Parsed with {@link URI} rather than by regex over the raw string, because the cases
   * that matter are the ones a substring check gets wrong: {@code github.com.evil.test},
   * {@code https://github.com@evil.test/a/b}, and a path that walks. The host must match
   * exactly, there must be no userinfo, and both segments must be names GitHub itself would
   * accept.
   */
  static String githubRepo(String repoUrl) {
    if (repoUrl == null || repoUrl.isBlank()) {
      return null;
    }
    URI uri;
    try {
      uri = new URI(repoUrl.trim());
    } catch (Exception malformed) {
      return null;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
      return null;
    }
    String host = uri.getHost();
    if (host == null) {
      return null;
    }
    host = host.toLowerCase(Locale.ROOT);
    if (!"github.com".equals(host) && !"www.github.com".equals(host)) {
      return null;
    }
    String path = uri.getPath() == null ? "" : uri.getPath();
    String[] parts = path.split("/");
    // a leading slash makes parts[0] empty, so owner and repo are 1 and 2
    if (parts.length != 3 || !parts[0].isEmpty()) {
      return null;
    }
    String owner = parts[1];
    String repo = parts[2].endsWith(".git")
        ? parts[2].substring(0, parts[2].length() - 4)
        : parts[2];
    if (!SEGMENT.matcher(owner).matches() || !SEGMENT.matcher(repo).matches()) {
      return null;
    }
    return owner + "/" + repo;
  }
}
