package io.hermes.missioncontrol.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Which requests are handed the Angular shell, and which fall through.
 *
 * <p>The dashboard is a deep-linked SPA served from the same origin as its API, so an unknown
 * path has to become {@code index.html} — but an API path must not. Returning the shell for
 * {@code /api/...} would answer a mistyped or removed endpoint with an HTML page and a 200,
 * which the client parses as JSON and reports as a corrupt response rather than a 404.
 */
class SpaFallbackResolverTest {

  /** Stands in for the bundled Angular build: src/test/resources/static really has index.html. */
  private final Resource bundled = new ClassPathResource("/static/");

  /** The classpath root, which has application.yml and schema.sql but no index.html — the
   *  shape of a jar built without the frontend. */
  private final Resource notBundled = new ClassPathResource("/");

  @Test
  void aFileThatExistsIsServedAsItself() throws Exception {
    Resource resolved = resolve("application.yml", notBundled);

    assertEquals("application.yml", resolved.getFilename());
    assertTrue(resolved.exists());
  }

  @Test
  void anUnknownRouteBecomesTheAngularShell() throws Exception {
    // a refresh on /agents/a-1 has to render the app, not a 404
    for (String path : new String[] {"agents/a-1", "mcp-servers", "index.html", ""}) {
      assertEquals("index.html", resolve(path, bundled).getFilename(), path);
    }
  }

  @Test
  void theShellIsOnlyOfferedWhenItIsActuallyThere() throws Exception {
    // Returning a Resource that does not exist is what a resolver must never do:
    // ResourceHttpRequestHandler calls lastModified() on whatever comes back, and the
    // FileNotFoundException escapes the handler as a 500 with a 45-line stack trace at
    // ERROR. Null instead, so Spring answers its own clean 404 and logs nothing.
    for (String path : new String[] {"agents/a-1", "mcp-servers", ""}) {
      assertNull(resolve(path, notBundled), path);
    }
  }

  @Test
  void anApiPathIsNeverAnsweredWithHtml() throws Exception {
    // the client parses these as JSON; an HTML 200 reads as a corrupt response, not a 404
    assertNull(resolve("api/agents", bundled));
    assertNull(resolve("api/", bundled));
  }

  @Test
  void theHealthAndRuntimeConfigPathsFallThroughToo() throws Exception {
    // health is polled by the launcher and config.js is generated per deployment; neither may
    // be shadowed by the shell when it is missing
    assertNull(resolve("health", bundled));
    assertNull(resolve("config.js", bundled));
    // only those exact paths, though — a route that merely starts with them is still a route
    assertEquals("index.html", resolve("healthcheck", bundled).getFilename());
    assertEquals("index.html", resolve("config.js.map", bundled).getFilename());
  }

  private Resource resolve(String path, Resource location) throws IOException {
    return WebConfig.resolveSpaPath(path, location);
  }
}
