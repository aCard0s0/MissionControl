package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The cache and degradation contract of {@link RegistryTagService}: what the image
 * picker is told while the registry is slow, stale, offline or answering oddly.
 */
class RegistryTagDegradationTest {

  private static final String REPO = "nousresearch/hermes-agent";
  private static final String FIRST_PAGE =
      "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page_size=100";
  private static final String SECOND_PAGE =
      "https://hub.docker.com/v2/repositories/nousresearch/hermes-agent/tags?page=2";
  private static final String OFF_HOST_PAGE =
      "https://evil.example/v2/repositories/nousresearch/hermes-agent/tags?page=2";

  private static final long START_MS = 1_770_000_000_000L;
  private static final long OK_TTL_MS = 600_000;
  private static final long ERROR_TTL_MS = 60_000;

  private final ObjectMapper json = new ObjectMapper();
  private final QueuedFetcher fetcher = new QueuedFetcher();
  private final AtomicLong clock = new AtomicLong(START_MS);
  private final RegistryTagService service =
      new RegistryTagService(json, true, fetcher, clock::get);

  @Test
  void aSuccessfulLookupReportsOkWithTheTagsAndAReadingTimestamp() {
    fetcher.queue(page(null, "latest", "v2026.8.3"));

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(RegistryTagService.OK, result.status());
    assertEquals(List.of("latest", "v2026.8.3"), names(result));
    assertNull(result.detail());
    assertEquals(Long.valueOf(START_MS), result.checkedAt());
    assertEquals(List.of(FIRST_PAGE), fetcher.urls);
  }

  @Test
  void aSecondCallWithinTheOkTtlIsServedFromCacheWithoutRefetching() {
    fetcher.queue(page(null, "latest", "v2026.8.3"));
    service.tags(REPO);

    advance(OK_TTL_MS - 1);
    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(1, fetcher.urls.size());
    assertEquals(RegistryTagService.OK, result.status());
    assertEquals(List.of("latest", "v2026.8.3"), names(result));
    // the timestamp belongs to the reading, not to the call, so the age shown is honest
    assertEquals(Long.valueOf(START_MS), result.checkedAt());
  }

  @Test
  void theCacheIsRefetchedOnceTheOkTtlHasElapsed() {
    fetcher.queue(page(null, "v2026.8.3"));
    fetcher.queue(page(null, "v2026.8.3", "v2026.9.1"));
    service.tags(REPO);

    advance(OK_TTL_MS + 1);
    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(2, fetcher.urls.size());
    // a release published during the TTL has to actually become visible
    assertEquals(List.of("v2026.8.3", "v2026.9.1"), names(result));
    assertEquals(Long.valueOf(START_MS + OK_TTL_MS + 1), result.checkedAt());
  }

  @Test
  void aFailureAfterASuccessKeepsServingTheStaleListAsCached() {
    fetcher.queue(page(null, "latest", "v2026.8.3"));
    service.tags(REPO);
    fetcher.queueFailure("hub.docker.com: connection refused");

    advance(OK_TTL_MS + 1);
    RegistryTagService.RemoteTags result = service.tags(REPO);

    // an outage must not empty the image picker: yesterday's tags are still pullable
    assertEquals(RegistryTagService.CACHED, result.status());
    assertEquals(List.of("latest", "v2026.8.3"), names(result));
    assertEquals("hub.docker.com: connection refused", result.detail());
  }

  @Test
  void aFailureWithNothingCachedReportsUnavailableWithTheDetailAndAnEmptyList() {
    fetcher.queueFailure("hub.docker.com: connection refused");

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(RegistryTagService.UNAVAILABLE, result.status());
    assertEquals(List.of(), result.tags());
    assertEquals("hub.docker.com: connection refused", result.detail());
    assertEquals(Long.valueOf(START_MS), result.checkedAt());
  }

  @Test
  void aSecondFailureWithinTheErrorTtlDoesNotRetryTheRegistry() {
    fetcher.queueFailure("hub.docker.com: connection refused");
    service.tags(REPO);

    advance(ERROR_TTL_MS - 1);
    RegistryTagService.RemoteTags result = service.tags(REPO);

    // the negative cache is what keeps an offline host answering fast instead of
    // paying the connect timeout on every poll
    assertEquals(1, fetcher.urls.size());
    assertEquals(RegistryTagService.UNAVAILABLE, result.status());
    assertEquals("hub.docker.com: connection refused", result.detail());
    assertEquals(Long.valueOf(START_MS), result.checkedAt());
  }

  @Test
  void theErrorTtlIsShorterThanTheOkTtlSoAnOutageRecoversQuickly() {
    fetcher.queueFailure("hub.docker.com: connection refused");
    fetcher.queue(page(null, "latest", "v2026.8.3"));
    service.tags(REPO);

    advance(ERROR_TTL_MS + 1);
    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(2, fetcher.urls.size());
    assertEquals(RegistryTagService.OK, result.status());
    assertEquals(List.of("latest", "v2026.8.3"), names(result));
    assertNull(result.detail());
    assertEquals(Long.valueOf(START_MS + ERROR_TTL_MS + 1), result.checkedAt());
  }

  @Test
  void lookupIsDisabledWhenTheFlagIsOff() {
    RegistryTagService disabled = new RegistryTagService(json, false, fetcher, clock::get);

    RegistryTagService.RemoteTags result = disabled.tags(REPO);

    assertEquals(RegistryTagService.DISABLED, result.status());
    assertEquals(List.of(), result.tags());
    // no lookup was attempted, so there is no reading to date
    assertNull(result.checkedAt());
    assertEquals(List.of(), fetcher.urls);
  }

  @Test
  void aRepositoryOutsideDockerHubIsUnsupportedAndNeverFetched() {
    RegistryTagService.RemoteTags result = service.tags("ghcr.io/nousresearch/hermes-agent");

    assertEquals(RegistryTagService.UNSUPPORTED, result.status());
    assertEquals(List.of(), result.tags());
    assertNull(result.checkedAt());
    // a Hub URL built from a foreign repository would enumerate the wrong image
    assertEquals(List.of(), fetcher.urls);
  }

  @Test
  void paginationFollowsTheNextLinkAndAccumulatesEveryPage() {
    fetcher.queue(page(SECOND_PAGE, "latest", "v2026.8.3"));
    fetcher.queue(page(null, "v2026.7.1"));

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(RegistryTagService.OK, result.status());
    assertEquals(List.of("latest", "v2026.8.3", "v2026.7.1"), names(result));
    assertEquals(List.of(FIRST_PAGE, SECOND_PAGE), fetcher.urls);
  }

  @Test
  void aNextLinkPointingAtAnotherHostStopsThePaginationRatherThanFollowingIt() {
    fetcher.queue(page(OFF_HOST_PAGE, "latest", "v2026.8.3"));

    RegistryTagService.RemoteTags result = service.tags(REPO);

    // a surprising response must not be able to steer the backend at an arbitrary host
    assertEquals(List.of(FIRST_PAGE), fetcher.urls);
    assertEquals(RegistryTagService.OK, result.status());
    assertEquals(List.of("latest", "v2026.8.3"), names(result));
  }

  @Test
  void aFailingPageMidPaginationDegradesRatherThanThrowing() {
    fetcher.queue(page(SECOND_PAGE, "latest", "v2026.8.3"));
    fetcher.queueFailure("hub.docker.com: 503 Service Unavailable");

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(2, fetcher.urls.size());
    // a truncated list is indistinguishable from a complete one to the caller, so a
    // half-read page is dropped and the status carries the failure instead
    assertEquals(RegistryTagService.UNAVAILABLE, result.status());
    assertEquals(List.of(), result.tags());
    assertEquals("hub.docker.com: 503 Service Unavailable", result.detail());
  }

  @Test
  void aVeryLongRegistryErrorIsTruncatedInTheStatusDetail() {
    String huge = "hub.docker.com: 502 Bad Gateway ".repeat(20).substring(0, 500);
    fetcher.queueFailure(huge);

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(RegistryTagService.UNAVAILABLE, result.status());
    // a proxy's HTML error page arrives as one enormous exception message, and the detail
    // is cached and re-served on every catalog response for the negative-cache window
    assertEquals(201, result.detail().length());
    assertEquals(huge.substring(0, 200) + "…", result.detail());
  }

  @Test
  void anExceptionWithNoMessageStillReportsSomethingUseful() {
    fetcher.queueFailure(new SocketTimeoutException());

    RegistryTagService.RemoteTags result = service.tags(REPO);

    assertEquals(RegistryTagService.UNAVAILABLE, result.status());
    // a bare timeout carries no message; "unavailable" with a blank reason leaves an
    // operator unable to tell a hung registry from a rejected request
    assertEquals("SocketTimeoutException", result.detail());
  }

  private void advance(long ms) {
    clock.addAndGet(ms);
  }

  private static List<String> names(RegistryTagService.RemoteTags result) {
    return result.tags().stream().map(ImageTagDto::tag).toList();
  }

  /** One Docker Hub tag page carrying {@code tags}, optionally linking on to {@code next}. */
  private static String page(String next, String... tags) {
    StringBuilder body = new StringBuilder("{\"count\": ").append(tags.length);
    if (next != null) {
      body.append(", \"next\": \"").append(next).append('"');
    }
    body.append(", \"results\": [");
    for (int i = 0; i < tags.length; i++) {
      if (i > 0) body.append(", ");
      body.append("{\"name\": \"").append(tags[i])
          .append("\", \"tag_status\": \"active\", \"full_size\": 1234")
          .append(", \"last_updated\": \"2026-08-13T10:25:55.603362Z\"")
          .append(", \"digest\": \"sha256:").append(i).append("\"}");
    }
    return body.append("]}").toString();
  }

  /**
   * Hands out queued page bodies (or failures) in order and records every URL asked for.
   * An unqueued fetch raises an {@link AssertionError}, which the service does not catch —
   * an unexpected outbound call fails the test instead of degrading it silently.
   */
  private static final class QueuedFetcher implements RegistryTagService.PageFetcher {

    private final Deque<Object> responses = new ArrayDeque<>();
    private final List<String> urls = new ArrayList<>();

    void queue(String body) {
      responses.add(body);
    }

    void queueFailure(String message) {
      responses.add(new IOException(message));
    }

    /** For failures whose type or absent message is the point, not the wording. */
    void queueFailure(Exception failure) {
      responses.add(failure);
    }

    @Override
    public String get(String url) throws Exception {
      urls.add(url);
      Object next = responses.poll();
      if (next == null) throw new AssertionError("unexpected registry fetch: " + url);
      if (next instanceof Exception failure) throw failure;
      return (String) next;
    }
  }
}
