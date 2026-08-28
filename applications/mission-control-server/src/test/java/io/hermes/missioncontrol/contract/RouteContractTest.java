package io.hermes.missioncontrol.contract;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * The other half of the wire contract: the addresses, not the payloads.
 *
 * <p>{@link ApiContractTest} pins the JSON keys a response carries, and {@link HttpSurfaceTest}
 * sweeps the routes this application maps. Neither says whether the dashboard calls those
 * routes. A controller can move from {@code @PutMapping} to {@code @PatchMapping}, or gain a
 * path segment, and every test on both sides stays green — the frontend's own suite asserts
 * only the URL its client composes, against a stubbed backend.
 *
 * <p>So the frontend publishes that list. Its route test exercises each client method once and
 * writes what went on the wire to {@code api-contract.txt}; this reads it back and asks the
 * real handler mapping to resolve every entry. Whichever side moves first fails.
 *
 * <p>Published rather than parsed — unlike {@link ApiContractTest}, which reads the frontend's
 * interface declarations directly. Paths are assembled from template literals and helpers
 * ({@code agentPath(ref)}), so there is no declaration to read; running the client is the only
 * honest way to learn what it asks for.
 *
 * <p>It has to be {@link RequestMappingHandlerMapping} specifically. {@code WebConfig} serves
 * the SPA from a resource handler that answers unknown paths with index.html, which is why a
 * mistyped endpoint is invisible in production — the client asks for JSON and gets a page.
 * Asking the annotation mapping directly means a path with no controller behind it comes back
 * unmapped instead of quietly succeeding.
 */
@SpringBootTest
@ActiveProfiles("test")
class RouteContractTest {

  @Autowired
  private RequestMappingHandlerMapping handlerMapping;

  @Test
  void everyRouteTheDashboardCallsIsMappedHere() {
    List<String> contract = readContract();
    List<String> unmapped = new ArrayList<>();
    List<String> wrongMethod = new ArrayList<>();

    for (String entry : contract) {
      String[] parts = entry.split(" ", 2);
      String method = parts[0];
      // the frontend records what it puts on the wire, so segments arrive percent-encoded
      String path = URI.create(parts[1]).getPath();

      MockHttpServletRequest request = new MockHttpServletRequest(method, path);
      ServletRequestPathUtils.parseAndCache(request);
      try {
        if (handlerMapping.getHandler(request) == null) unmapped.add(entry);
      } catch (HttpRequestMethodNotSupportedException e) {
        wrongMethod.add(entry + " — the path exists but allows "
            + String.join("/", e.getSupportedMethods()));
      } catch (Exception e) {
        fail("could not resolve " + entry + ": " + e);
      }
    }

    assertTrue(unmapped.isEmpty() && wrongMethod.isEmpty(), () -> {
      StringBuilder report = new StringBuilder("the dashboard calls routes this server does not serve:");
      for (String entry : unmapped) report.append("\n  ").append(entry);
      for (String entry : wrongMethod) report.append("\n  ").append(entry);
      return report.append("\n\nEither a controller moved and the dashboard was not updated, or ")
          .append("the published list is stale — re-run the frontend suite to rewrite it.").toString();
    });
  }

  @Test
  void thePublishedListCoversTheWholeClient() {
    // a truncated or half-written file would let the check above pass while covering nothing
    assertTrue(readContract().size() > 50,
        () -> "expected the frontend's whole route table in " + RepoDocs.contractFile()
            + "; run `npm run test:coverage` in applications/mission-control-fe to rewrite it");
  }

  private static List<String> readContract() {
    Path file = RepoDocs.contractFile();
    assumeTrue(file != null, "mission-control-fe is not in this checkout — nothing published to check");
    try {
      return Files.readAllLines(file).stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
  }
}
