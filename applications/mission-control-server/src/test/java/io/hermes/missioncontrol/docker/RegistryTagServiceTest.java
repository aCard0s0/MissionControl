package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RegistryTagServiceTest {

  private final ObjectMapper json = new ObjectMapper();

  /** Shape of a real hub.docker.com tag page, trimmed to the fields that are read. */
  private static final String PAGE = """
      {
        "count": 21,
        "next": "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page=2",
        "results": [
          {"name": "latest", "tag_status": "active", "full_size": 1234,
           "last_updated": "2026-08-13T10:25:55.603362Z", "digest": "sha256:aaa"},
          {"name": "v2026.8.3", "tag_status": "active", "full_size": 5678,
           "last_updated": "2026-08-03T17:10:04.321211Z", "digest": "sha256:bbb"},
          {"name": "v2026.1.1", "tag_status": "inactive",
           "last_updated": "2026-01-01T00:00:00Z", "digest": "sha256:ccc"}
        ]
      }
      """;

  @Test
  void readsNameSizeDigestAndLastUpdated() throws Exception {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(json.readTree(PAGE));

    assertEquals(2, tags.size());
    ImageTagDto release = tags.get(1);
    assertEquals("v2026.8.3", release.tag());
    assertTrue(release.remote());
    assertEquals(false, release.pulled());   // pulled-ness comes from the daemon, not the registry
    assertEquals(5678L, release.sizeBytes());
    assertEquals("sha256:bbb", release.digest());
    assertEquals(
        OffsetDateTime.parse("2026-08-03T17:10:04.321211Z").toInstant().toEpochMilli(),
        release.lastUpdated());
  }

  @Test
  void oneTagsDigestIsReadFromHubCachedAndServedStaleThroughAnOutage() {
    long[] now = {0};
    int[] calls = {0};
    String[] body = {"{\"name\": \"latest\", \"digest\": \"sha256:aaa\"}"};
    RegistryTagService.PageFetcher fetcher = url -> {
      calls[0]++;
      assertEquals("https://hub.docker.com/v2/repositories/mcp/playwright/tags/latest", url);
      if (body[0] == null) throw new IllegalStateException("registry responded 503");
      return body[0];
    };
    RegistryTagService service = new RegistryTagService(json, true, fetcher, () -> now[0]);

    assertEquals("sha256:aaa", service.remoteDigest("mcp/playwright:latest"));
    assertEquals("sha256:aaa", service.remoteDigest("mcp/playwright"), "no tag means latest");
    assertEquals(1, calls[0], "the second read is the cache");

    // past the TTL the registry is asked again; when it is down the last answer still stands
    now[0] = 600_001;
    body[0] = null;
    assertEquals("sha256:aaa", service.remoteDigest("mcp/playwright:latest"));
    assertEquals(2, calls[0]);

    // not Hub, and disabled: nothing is even attempted
    assertNull(service.remoteDigest("ghcr.io/acme/tool:1"));
    assertNull(service.remoteDigest(null));
    assertNull(new RegistryTagService(json, false, fetcher, () -> 0).remoteDigest("mcp/playwright:latest"));
    assertEquals(2, calls[0]);
  }

  @Test
  void aDigestLookupThatNeverSucceededIsNullAndNotRetriedUntilTheNegativeTtlPasses() {
    long[] now = {0};
    int[] calls = {0};
    RegistryTagService.PageFetcher fetcher = url -> { calls[0]++; throw new IllegalStateException("registry responded 503"); };
    RegistryTagService service = new RegistryTagService(json, true, fetcher, () -> now[0]);

    assertNull(service.remoteDigest("mcp/playwright:latest"));
    assertNull(service.remoteDigest("mcp/playwright:latest"));
    assertEquals(1, calls[0], "an offline registry is asked once a minute, not once a poll");

    now[0] = 60_001;
    assertNull(service.remoteDigest("mcp/playwright:latest"));
    assertEquals(2, calls[0]);
  }

  @Test
  void theDigestCacheSweepsWhatHasGoneUnaskedForPastItsTtl() {
    long[] now = {0};
    RegistryTagService.PageFetcher fetcher = url -> "{\"digest\": \"sha256:" + url.hashCode() + "\"}";
    RegistryTagService service = new RegistryTagService(json, true, fetcher, () -> now[0]);

    // a catalog's worth of distinct images, then enough time for all of them to expire
    for (int i = 0; i < 300; i++) service.remoteDigest("acme/tool-" + i + ":1");
    now[0] = 600_001;
    // the write that crosses the sweep threshold drops the expired ones rather than growing forever
    assertEquals("sha256:" + "https://hub.docker.com/v2/repositories/acme/fresh/tags/1".hashCode(),
        service.remoteDigest("acme/fresh:1"));
    assertEquals("sha256:" + "https://hub.docker.com/v2/repositories/acme/tool-0/tags/1".hashCode(),
        service.remoteDigest("acme/tool-0:1"), "an evicted key is simply fetched again");
  }

  @Test
  void dropsInactiveTagsBecauseTheyCannotBePulled() throws Exception {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(json.readTree(PAGE));
    assertTrue(tags.stream().noneMatch(t -> "v2026.1.1".equals(t.tag())));
  }

  @Test
  void toleratesAMissingOrEmptyResultsArray() throws Exception {
    assertEquals(List.of(), RegistryTagService.parseHubPage(json.readTree("{}")));
    assertEquals(List.of(), RegistryTagService.parseHubPage(json.readTree("{\"results\": []}")));
    assertEquals(List.of(), RegistryTagService.parseHubPage(null));
  }

  @Test
  void followsPaginationOnlyWithinDockerHub() throws Exception {
    assertEquals(
        "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page=2",
        RegistryTagService.nextHubPage(json.readTree(PAGE)));
  }

  @Test
  void refusesAPageLinkPointingAtAnotherHost() throws Exception {
    // a 'next' is followed without further checks, so it must not be able to
    // redirect the backend at an arbitrary host
    assertNull(RegistryTagService.nextHubPage(
        json.readTree("{\"next\": \"https://evil.example/v2/repositories/x/tags\"}")));
    assertNull(RegistryTagService.nextHubPage(json.readTree("{\"next\": null}")));
    assertNull(RegistryTagService.nextHubPage(json.readTree("{}")));
  }

  @Test
  void reportsUnsupportedForRepositoriesOutsideDockerHub() {
    RegistryTagService.RemoteTags result =
        new RegistryTagService(json, true).tags("ghcr.io/nousresearch/hermes-agent");

    assertEquals(RegistryTagService.UNSUPPORTED, result.status());
    assertEquals(List.of(), result.tags());
    assertNull(result.checkedAt());
  }

  // ── page shapes the hub actually returns ─────────────────────────────────

  @Test
  void aPageThatIsNotATagPageYieldsNoTagsRatherThanFailing() {
    assertTrue(RegistryTagService.parseHubPage(null).isEmpty());
    assertTrue(RegistryTagService.parseHubPage(read("{}")).isEmpty());
    assertTrue(RegistryTagService.parseHubPage(read("{\"results\": \"nope\"}")).isEmpty());
    assertTrue(RegistryTagService.parseHubPage(read("{\"results\": []}")).isEmpty());
  }

  @Test
  void aTagWithNoUsableNameIsSkipped() {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(read("""
        {"results": [{"name": ""}, {"tag_status": "active"}, {"name": "keep"}]}
        """));

    assertEquals(List.of("keep"), tags.stream().map(ImageTagDto::tag).toList());
  }

  @Test
  void aTagWithNoStatusIsAssumedPullableAndOptionalFieldsMayBeAbsent() {
    // older hub responses omit tag_status entirely
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(read("""
        {"results": [{"name": "v1"}]}
        """));

    ImageTagDto tag = tags.getFirst();
    assertEquals("v1", tag.tag());
    assertNull(tag.sizeBytes());
    assertNull(tag.digest());
    assertNull(tag.lastUpdated());
  }

  @Test
  void anUnparseableTimestampDoesNotSinkTheTag() {
    List<ImageTagDto> tags = RegistryTagService.parseHubPage(read("""
        {"results": [{"name": "v1", "last_updated": "yesterday"}]}
        """));

    assertEquals("v1", tags.getFirst().tag());
    assertNull(tags.getFirst().lastUpdated());
  }

  // ── paging ──────────────────────────────────────────────────────────────

  @Test
  void aNextLinkPointingAnywhereButTheHubIsDiscarded() {
    // following it would let a surprising response steer our requests at another host
    assertNull(RegistryTagService.nextHubPage(read("""
        {"next": "https://evil.test/v2/repositories/x/tags?page=2"}
        """)));
    assertNull(RegistryTagService.nextHubPage(read("{}")));
    assertNull(RegistryTagService.nextHubPage(null));
    assertEquals("https://hub.docker.com/v2/repositories/x/tags?page=2",
        RegistryTagService.nextHubPage(read("""
            {"next": "https://hub.docker.com/v2/repositories/x/tags?page=2"}
            """)));
  }

  private JsonNode read(String body) {
    if (body == null) return null;
    try {
      return json.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ── the service around the parser ────────────────────────────────────────

  @Test
  void tagLookupCanBeSwitchedOffEntirely() {
    RegistryTagService disabled = new RegistryTagService(json, false, url -> PAGE, () -> 0L);

    RegistryTagService.RemoteTags tags = disabled.tags("nousresearch/hermes-agent");

    assertEquals("disabled", tags.status());
    assertTrue(tags.tags().isEmpty());
    assertEquals("registry tag lookup is disabled", tags.detail());
  }

  @Test
  void aRepositoryThatIsNotOnDockerHubIsReportedAsUnsupported() {
    RegistryTagService service = new RegistryTagService(json, true, url -> PAGE, () -> 0L);

    RegistryTagService.RemoteTags tags = service.tags("ghcr.io/nousresearch/hermes-agent");

    assertEquals("unsupported", tags.status());
    assertEquals("remote tag listing supports Docker Hub only", tags.detail());
  }

  @Test
  void aSuccessfulLookupIsServedFromCacheOnTheNextCall() {
    // the image page polls this; one lookup per ten seconds is the point of the cache
    AtomicInteger fetches = new AtomicInteger();
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      fetches.incrementAndGet();
      return url.contains("page=2") ? "{\"results\": []}" : PAGE;
    }, () -> 1_000L);

    RegistryTagService.RemoteTags first = service.tags("nousresearch/hermes-agent");
    RegistryTagService.RemoteTags second = service.tags("nousresearch/hermes-agent");

    assertEquals("ok", first.status());
    assertEquals(2, first.tags().size());
    assertEquals(first.tags().size(), second.tags().size());
    assertEquals(2, fetches.get(), "the second call fetched nothing: page 1 and its 'next' only");
  }

  @Test
  void aFailedLookupDegradesToAnErrorStatusAndIsAlsoCached() {
    // a registry outage must not turn every poll into a fresh timeout
    AtomicInteger fetches = new AtomicInteger();
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      fetches.incrementAndGet();
      throw new IllegalStateException("registry responded 503");
    }, () -> 1_000L);

    RegistryTagService.RemoteTags first = service.tags("nousresearch/hermes-agent");
    service.tags("nousresearch/hermes-agent");

    assertEquals(RegistryTagService.UNAVAILABLE, first.status());
    assertTrue(first.tags().isEmpty());
    assertTrue(first.detail().contains("503"), first.detail());
    assertEquals(1, fetches.get(), "the failure is cached too, on a shorter clock");
  }

  @Test
  void anExpiredCacheEntryIsFetchedAgain() {
    AtomicInteger fetches = new AtomicInteger();
    AtomicLong now = new AtomicLong(1_000L);
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      fetches.incrementAndGet();
      return url.contains("page=2") ? "{\"results\": []}" : PAGE;
    }, now::get);

    service.tags("nousresearch/hermes-agent");
    now.addAndGet(600_001L);
    service.tags("nousresearch/hermes-agent");

    assertEquals(4, fetches.get(), "two pages, twice");
  }

  @Test
  void aFailureWithNoMessageStillCarriesSomethingTheOperatorCanRead() {
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      throw new IllegalStateException();
    }, () -> 1_000L);

    assertEquals("IllegalStateException", service.tags("nousresearch/hermes-agent").detail());
  }

  @Test
  void aVeryLongFailureMessageIsTruncated() {
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      throw new IllegalStateException("x".repeat(500));
    }, () -> 1_000L);

    String detail = service.tags("nousresearch/hermes-agent").detail();
    assertTrue(detail.length() <= 201, "was " + detail.length());
    assertTrue(detail.endsWith("…"));
  }

  @Test
  void aFailureAfterAGoodLookupKeepsServingTheTagsItAlreadyHad() {
    // a registry blip must not empty the tag picker the operator was just using
    AtomicLong now = new AtomicLong(1_000L);
    AtomicBoolean broken = new AtomicBoolean(false);
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      if (broken.get()) throw new IllegalStateException("registry responded 503");
      return url.contains("page=2") ? "{\"results\": []}" : PAGE;
    }, now::get);
    service.tags("nousresearch/hermes-agent");

    broken.set(true);
    now.addAndGet(600_001L);
    RegistryTagService.RemoteTags stale = service.tags("nousresearch/hermes-agent");

    assertEquals(RegistryTagService.CACHED, stale.status());
    assertEquals(2, stale.tags().size(), "the previous good list is kept");
    assertTrue(stale.detail().contains("503"));

    // and the negative cache serves the same stale list until it expires
    RegistryTagService.RemoteTags again = service.tags("nousresearch/hermes-agent");
    assertEquals(RegistryTagService.CACHED, again.status());
    assertEquals(2, again.tags().size());
  }

  @Test
  void aConcurrentLookupReadsWhatIsThereRatherThanStackingRequests() throws Exception {
    // two dashboard clients polling the same image must not become two registry fetches
    CountDownLatch inFetch = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      inFetch.countDown();
      release.await(5, TimeUnit.SECONDS);
      return url.contains("page=2") ? "{\"results\": []}" : PAGE;
    }, () -> 1_000L);

    Thread holder = Thread.ofPlatform().start(() -> service.tags("nousresearch/hermes-agent"));
    assertTrue(inFetch.await(5, TimeUnit.SECONDS));

    RegistryTagService.RemoteTags whileFetching = service.tags("nousresearch/hermes-agent");

    assertEquals(RegistryTagService.UNAVAILABLE, whileFetching.status());
    assertEquals("registry lookup in progress", whileFetching.detail());
    release.countDown();
    holder.join(java.time.Duration.ofSeconds(10));
  }

  @Test
  void pagingStopsAtTheHardPageCap() {
    // a repository with thousands of tags would otherwise walk the whole registry on one poll
    AtomicInteger fetches = new AtomicInteger();
    RegistryTagService service = new RegistryTagService(json, true, url -> {
      fetches.incrementAndGet();
      return """
          {"next": "https://hub.docker.com/v2/repositories/x/tags?page=9",
           "results": [{"name": "v1", "tag_status": "active"}]}
          """;
    }, () -> 1_000L);

    RegistryTagService.RemoteTags tags = service.tags("nousresearch/hermes-agent");

    assertEquals(5, fetches.get(), "five pages is the cap");
    assertEquals(5, tags.tags().size());
  }

  @Test
  void aBlankTimestampOrFailureMessageDegradesQuietly() {
    assertNull(RegistryTagService.parseHubPage(read("""
        {"results": [{"name": "v1", "last_updated": "   "}]}
        """)).getFirst().lastUpdated());

    RegistryTagService service = new RegistryTagService(json, true, url -> {
      throw new IllegalStateException("   ");
    }, () -> 1_000L);
    assertEquals("IllegalStateException", service.tags("nousresearch/hermes-agent").detail());
  }
}
