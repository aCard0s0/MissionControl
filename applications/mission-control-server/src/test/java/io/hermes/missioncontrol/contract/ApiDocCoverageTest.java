package io.hermes.missioncontrol.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * Every route the dashboard calls appears in {@code docs/api.md}.
 *
 * <p>{@link RouteContractTest} proves the frontend and the controllers agree on the wire.
 * Neither side says whether a human was told. api.md is hand-written prose — nothing generated
 * it and nothing read it back — so it drifted the way hand-maintained mirrors do: whole route
 * families shipped without a row, and its roadmap listed a feature that had been live for
 * releases. That is not a documentation nicety here, because api.md is where the *why* of each
 * endpoint lives, and the generated contract file cannot hold that.
 *
 * <p><b>The templates come from Spring, not from parsing.</b> The published contract records
 * concrete urls — {@code /api/agents/dh-local/c-1/atlas/logs} — where a path segment and a
 * value are syntactically identical, so no regex can tell {@code atlas} from {@code logs}.
 * Resolving each entry against the handler mapping hands back the pattern it matched
 * ({@code /api/agents/{hostId}/{containerId}/{name}/logs}), which is the shape api.md writes.
 *
 * <p>Deliberately permissive in two ways, because a false alarm here costs more than a missed
 * row: a route counts as documented if it is mentioned <em>anywhere</em> in the file, table or
 * prose, and api.md's {@code …/{name}/logs} shorthand is matched as a suffix of the full
 * pattern.
 *
 * <p>That second rule has a known blind spot, and it is real rather than theoretical: two
 * routes sharing a method and a tail satisfy each other. {@code POST …/{name}/pause} pauses a
 * profile and {@code POST …/{name}/cron/{jobId}/pause} pauses one job, and either row makes
 * both look documented. Both are written down today, so the gap is closed in fact — but
 * deleting one row would not fail this test. Resolving the shorthand exactly would mean
 * knowing the prefix a human infers from the section, and {@code …} elides a different number
 * of segments in different rows of the same table. This is a floor, not a proof.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiDocCoverageTest {

  /** A `METHOD /path` mention anywhere in the doc — inside a table cell or mid-sentence. */
  private static final Pattern DOCUMENTED =
      Pattern.compile("`(GET|POST|PUT|PATCH|DELETE)\\s+(/[^`\\s?]*|…[^`\\s?]*)");

  /** Any `{placeholder}` — the names differ between the doc and the mapping, the shape does not. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

  @Autowired
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void everyRouteTheDashboardCallsIsWrittenDownInApiMd() {
    Path doc = RepoDocs.apiDoc();
    assumeTrue(doc != null, "docs/api.md is not in this checkout — nothing to check against");

    Set<String> documented = mentionsIn(doc);
    List<String> undocumented = new ArrayList<>();

    for (String pattern : patternsTheDashboardCalls()) {
      if (!isDocumented(pattern, documented)) undocumented.add(pattern);
    }

    assertTrue(undocumented.isEmpty(), () -> {
      StringBuilder report = new StringBuilder(
          "these routes are on the wire but appear nowhere in docs/api.md:");
      for (String route : undocumented) report.append("\n  ").append(route);
      return report.append("\n\nAdd a row for each — the table nearest its section — or, if the ")
          .append("dashboard should not be calling it, take the call out of the frontend.")
          .toString();
    });
  }

  /** `METHOD pattern` for every entry in the published contract, as the server matched it. */
  private List<String> patternsTheDashboardCalls() {
    Set<String> patterns = new LinkedHashSet<>();
    for (String entry : contract()) {
      String[] parts = entry.split(" ", 2);
      String method = parts[0];
      MockHttpServletRequest request =
          new MockHttpServletRequest(method, URI.create(parts[1]).getPath());
      ServletRequestPathUtils.parseAndCache(request);
      try {
        if (handlerMapping.getHandler(request) == null) continue;   // RouteContractTest owns that
      } catch (Exception unmappedOrWrongMethod) {
        continue;
      }
      Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      if (matched != null) patterns.add(method + " " + normalize(matched.toString()));
    }
    return List.copyOf(patterns);
  }

  @Test
  void theDocIsBeingReadAtAll() {
    Path doc = RepoDocs.apiDoc();
    assumeTrue(doc != null, "docs/api.md is not in this checkout");
    // a regex that stopped matching would make the check above pass while covering nothing —
    // the same way a truncated contract file would, which RouteContractTest guards separately
    assertTrue(mentionsIn(doc).size() > 60,
        () -> "expected api.md's route mentions to parse; found " + mentionsIn(doc).size());
  }

  private static Set<String> mentionsIn(Path doc) {
    String text;
    try {
      text = Files.readString(doc);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + doc, e);
    }
    Set<String> mentions = new LinkedHashSet<>();
    Matcher m = DOCUMENTED.matcher(text);
    while (m.find()) mentions.add(m.group(1) + " " + normalize(m.group(2)));
    return mentions;
  }

  /**
   * A documented mention covers a pattern when it is the whole path, or the tail of it — which
   * is how api.md's `…/{name}/logs` shorthand stands in for a section's full prefix.
   */
  private static boolean isDocumented(String pattern, Set<String> documented) {
    if (documented.contains(pattern)) return true;
    String method = pattern.substring(0, pattern.indexOf(' '));
    String path = pattern.substring(pattern.indexOf(' ') + 1);
    return documented.stream().anyMatch(mention -> {
      if (!mention.startsWith(method + " ")) return false;
      String shorthand = mention.substring(method.length() + 1);
      return shorthand.startsWith("…") && path.endsWith(shorthand.substring(1));
    });
  }

  /** Trailing slash and placeholder names carry no meaning for this comparison. */
  private static String normalize(String path) {
    String collapsed = PLACEHOLDER.matcher(path).replaceAll("{}");
    return collapsed.length() > 1 && collapsed.endsWith("/")
        ? collapsed.substring(0, collapsed.length() - 1)
        : collapsed;
  }

  private static List<String> contract() {
    Path file = RepoDocs.contractFile();
    assumeTrue(file != null, "mission-control-fe is not in this checkout — nothing published");
    try {
      return Files.readAllLines(file).stream()
          .map(String::trim).filter(line -> !line.isEmpty()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
  }
}
