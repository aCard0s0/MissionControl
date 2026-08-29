package io.hermes.missioncontrol.skills;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.skills.UpstreamCheck.Upstream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Checking whether a skill's source repository has moved on.
 *
 * <p>Two things here are worth more than the happy path. The URL parse decides whether a
 * string an operator typed can make this server issue a request somewhere of their choosing,
 * so the hostile forms are the first block below. And the comparison deliberately refuses to
 * order versions, because {@code version} is free text: reporting "behind" from a guess would
 * be worse than reporting "different".
 */
class UpstreamCheckTest {

  /** A fetcher that answers from a script and records what it was asked for. */
  private static final class FakeGithub implements UpstreamCheck.Fetcher {
    final List<String> asked = new ArrayList<>();
    String releases;
    String tags;
    RuntimeException blowUp;

    @Override
    public String get(String url) {
      asked.add(url);
      if (blowUp != null) {
        throw blowUp;
      }
      return url.contains("/releases/latest") ? releases : tags;
    }
  }

  private final AtomicLong now = new AtomicLong(1_000_000);

  private UpstreamCheck check(FakeGithub github) {
    return new UpstreamCheck(new ObjectMapper(), github, now::get);
  }

  private static FakeGithub releasing(String tagName) {
    FakeGithub github = new FakeGithub();
    github.releases = "{\"tag_name\":\"" + tagName + "\"}";
    return github;
  }

  // ── the URL parse ──────────────────────────────────────────────────────────

  @Test
  void onlyARealGithubRepositoryUrlIsEverResolved() {
    assertEquals("owner/repo", UpstreamCheck.githubRepo("https://github.com/owner/repo"));
    assertEquals("owner/repo", UpstreamCheck.githubRepo("https://github.com/owner/repo.git"));
    assertEquals("owner/repo", UpstreamCheck.githubRepo("https://www.github.com/owner/repo"));
    assertEquals("owner/repo", UpstreamCheck.githubRepo("HTTPS://GitHub.com/owner/repo"));
    assertEquals("o.w-n_r/re.po-1", UpstreamCheck.githubRepo("https://github.com/o.w-n_r/re.po-1"));
  }

  @Test
  void aUrlThatOnlyLooksLikeGithubResolvesToNothing() {
    // each of these would be a request this server makes to somewhere an operator chose,
    // which is a different and much worse feature than checking a version
    for (String url : List.of(
        "https://github.com.evil.test/owner/repo",     // suffix on the host
        "https://evil.test/github.com/owner/repo",     // host in the path
        "https://github.com@evil.test/owner/repo",     // userinfo before the real host
        "https://user:pw@github.com/owner/repo",       // credentials, even on the real host
        "http://github.com/owner/repo",                // not https
        "file:///etc/passwd",
        "https://127.0.0.1/owner/repo",
        "https://[::1]/owner/repo",
        "https://github.com/owner",                    // no repository
        "https://github.com/owner/repo/tree/main",     // deeper than a repository root
        "https://github.com//repo",                    // empty owner
        "https://github.com/owner/",                   // empty repository
        "https://github.com/../../etc/passwd",
        "https://github.com/owner/re po",              // space is not a github name
        "not a url at all",
        "")) {
      assertNull(UpstreamCheck.githubRepo(url), "resolved a URL it should not have: " + url);
    }
    assertNull(UpstreamCheck.githubRepo(null));
  }

  @Test
  void aRepositoryUrlThatCannotBeResolvedNeverReachesTheNetwork() {
    FakeGithub github = releasing("v2.0");

    Upstream answer = check(github).check("https://evil.test/owner/repo", "1.0");

    assertEquals(UpstreamCheck.UNSUPPORTED, answer.status());
    assertEquals(List.of(), github.asked, "a request was made for a URL that did not parse");
  }

  @Test
  void theUrlItRequestsIsBuiltFromTheTwoParsedWordsAndNothingElse() {
    FakeGithub github = releasing("v2.0");

    check(github).check("https://github.com/owner/repo.git", "1.0");

    assertEquals("https://api.github.com/repos/owner/repo/releases/latest",
        github.asked.getFirst());
  }

  // ── comparing ──────────────────────────────────────────────────────────────

  @Test
  void aMatchingVersionReadsAsCurrentWhicheverSideCarriesTheV() {
    for (String[] pair : List.of(
        new String[] {"1.0", "1.0"},
        new String[] {"v1.0", "1.0"},
        new String[] {"1.0", "v1.0"},
        new String[] {" V1.0 ", "v1.0"})) {
      Upstream answer = check(releasing(pair[1])).check("https://github.com/o/r", pair[0]);

      assertEquals(UpstreamCheck.CURRENT, answer.status(),
          "local=" + pair[0] + " upstream=" + pair[1]);
    }
  }

  @Test
  void aDifferentVersionReadsAsUpdateAndNamesBothSides() {
    Upstream answer = check(releasing("v2.1")).check("https://github.com/o/r", "1.0");

    assertEquals(UpstreamCheck.UPDATE, answer.status());
    assertEquals("v2.1", answer.latest());
    assertTrue(answer.detail().contains("v2.1"), answer.detail());
    assertTrue(answer.detail().contains("1.0"), answer.detail());
  }

  @Test
  void itReportsDifferenceRatherThanOrder() {
    // 1.10 is ahead of 1.9 under semver and behind it as a decimal, and `version` is free
    // text an operator typed — so this says "different" and lets the person decide
    Upstream ahead = check(releasing("1.9")).check("https://github.com/o/r", "1.10");

    assertEquals(UpstreamCheck.UPDATE, ahead.status());
    assertTrue(ahead.detail().contains("upstream is at 1.9"), ahead.detail());
  }

  @Test
  void aRowWithNoVersionStillLearnsWhatUpstreamHas() {
    Upstream answer = check(releasing("v2.0")).check("https://github.com/o/r", "  ");

    assertEquals(UpstreamCheck.UNKNOWN, answer.status());
    assertEquals("v2.0", answer.latest());
  }

  // ── reading ────────────────────────────────────────────────────────────────

  @Test
  void aRepositoryWithNoReleasesFallsBackToItsNewestTag() {
    FakeGithub github = new FakeGithub();
    github.releases = null;                                   // 404 from releases/latest
    github.tags = "[{\"name\":\"v3.0\"}]";

    Upstream answer = check(github).check("https://github.com/o/r", "1.0");

    assertEquals(UpstreamCheck.UPDATE, answer.status());
    assertEquals("v3.0", answer.latest());
    assertEquals(2, github.asked.size(), github.asked.toString());
  }

  @Test
  void aRepositoryWithNeitherIsUnsupportedRatherThanBroken() {
    FakeGithub github = new FakeGithub();
    github.releases = null;
    github.tags = "[]";

    Upstream answer = check(github).check("https://github.com/o/r", "1.0");

    assertEquals(UpstreamCheck.UNSUPPORTED, answer.status());
    assertTrue(answer.detail().contains("no releases or tags"), answer.detail());
  }

  @Test
  void anOutageDegradesRatherThanThrowing() {
    FakeGithub github = new FakeGithub();
    github.blowUp = new IllegalStateException("github responded 503");

    Upstream answer = check(github).check("https://github.com/o/r", "1.0");

    assertEquals(UpstreamCheck.UNAVAILABLE, answer.status());
    assertNull(answer.latest());
  }

  // ── caching ────────────────────────────────────────────────────────────────

  @Test
  void aSecondCheckInsideTheWindowAsksGithubNothing() {
    FakeGithub github = releasing("v2.0");
    UpstreamCheck upstream = check(github);

    upstream.check("https://github.com/o/r", "1.0");
    now.addAndGet(60_000);
    upstream.check("https://github.com/o/r", "1.0");

    assertEquals(1, github.asked.size(), github.asked.toString());
  }

  @Test
  void aFailedLookupIsRetriedSoonerThanASuccessfulOne() {
    FakeGithub github = new FakeGithub();
    github.blowUp = new IllegalStateException("down");
    UpstreamCheck upstream = check(github);

    upstream.check("https://github.com/o/r", "1.0");
    now.addAndGet(90_000);                       // past the error TTL, inside the ok one
    upstream.check("https://github.com/o/r", "1.0");

    assertEquals(2, github.asked.size(), "a negative cache outlived its minute");
  }

  @Test
  void theCachedReadingIsRecomparedAgainstWhatTheRowNowSays() {
    // the row's own version changes without upstream moving; a cached "update available"
    // on a skill the operator has since bumped is exactly the wrong answer
    FakeGithub github = releasing("v2.0");
    UpstreamCheck upstream = check(github);

    assertEquals(UpstreamCheck.UPDATE, upstream.check("https://github.com/o/r", "1.0").status());
    assertEquals(UpstreamCheck.CURRENT, upstream.check("https://github.com/o/r", "2.0").status());
    assertEquals(1, github.asked.size(), "the recompare cost a second lookup");
  }

  @Test
  void anExpiredEntryIsSweptOnTheNextWriteRatherThanKeptForever() {
    // the cache key is operator-supplied, so it would otherwise grow with every repository
    // anyone ever checked
    FakeGithub github = releasing("v2.0");
    UpstreamCheck upstream = check(github);

    upstream.check("https://github.com/o/one", "1.0");
    now.addAndGet(OK_TTL_PLUS);
    upstream.check("https://github.com/o/two", "1.0");

    assertEquals(1, upstream.cachedCount(), "the expired entry outlived its window");
  }

  private static final long OK_TTL_PLUS = 600_001;
}
