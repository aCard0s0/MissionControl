package io.hermes.missioncontrol.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

/**
 * The HTTP surface as the browser meets it, through the whole application rather than one
 * controller.
 *
 * <p>Every other test in this suite builds a controller with {@code standaloneSetup} or calls a
 * collaborator directly, so nothing notices what only the assembled stack decides: whether the
 * SPA fallback shadows an API path, whether CORS still admits the dev origin, whether the
 * terminal's origin guard is actually attached to its endpoint, and whether a malformed request
 * reaches the advice's catch-all as a 500.
 *
 * <p>The last of those is a sweep rather than a list: it walks every mutating route the
 * application maps and asserts none of them answers 5xx to a body it should reject. A 500 there
 * reports a client mistake as a Mission Control defect — complete with a stack trace at ERROR —
 * and pages whoever watches the 5xx rate. New endpoints are covered the day they are added.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
class HttpSurfaceTest {

  /** Placeholder path variables. The test profile seeds no hosts, so nothing resolves. */
  private static final String HOST = "dh-does-not-exist";

  @Autowired
  private MockMvc mvc;

  @Autowired
  private RequestMappingHandlerMapping mappings;

  @LocalServerPort
  private int port;

  // ── malformed input never becomes a 500 ─────────────────────────────────

  @Test
  void noMutatingRouteAnswersAServerErrorToABodyItShouldReject() throws Exception {
    List<String> offenders = new ArrayList<>();

    for (Route route : mutatingRoutes()) {
      for (String body : List.of("", "{}")) {
        MvcResult result = mvc.perform(request(route.method(), route.uri())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body)).andReturn();
        int status = result.getResponse().getStatus();
        if (status >= 500) {
          offenders.add(route + " with body '" + body + "' → " + status
              + " (" + failureOf(result) + ")");
        }
      }
    }

    assertTrue(offenders.isEmpty(),
        "a rejected request must be a 4xx, not a 5xx:\n  " + String.join("\n  ", offenders));
  }

  @Test
  void theSweepActuallyReachesEveryMutatingRoute() {
    // a guard on the guard: if the mapping walk silently found nothing, the sweep above would
    // pass while testing no endpoint at all
    Set<Route> routes = mutatingRoutes();

    assertTrue(routes.size() >= 30, "expected the whole mutating surface, found " + routes.size());
    assertTrue(routes.stream().anyMatch(r -> r.uri().startsWith("/api/agents")));
    assertTrue(routes.stream().anyMatch(r -> r.uri().startsWith("/api/mcp-servers")));
    assertTrue(routes.stream().anyMatch(r -> r.method() == HttpMethod.DELETE));
  }

  // ── the SPA fallback and the API must not shadow each other ─────────────

  @Test
  void aDeepLinkResolvesToTheAngularShell() throws Exception {
    // a refresh on /agents/a-1 has to render the app; the router takes it from there
    for (String path : List.of("/agents/a-1", "/mcp-servers", "/fleet")) {
      MvcResult result = mvc.perform(get(path)).andReturn();
      assertEquals(200, result.getResponse().getStatus(), path);
      assertTrue(result.getResponse().getContentAsString().contains("angular-shell-placeholder"),
          path + " did not serve the shell");
    }
  }

  @Test
  void anUnknownApiPathIs404AndNeverTheShell() throws Exception {
    // answering HTML with a 200 makes a mistyped or removed endpoint look like a corrupt
    // response to the client, which parses it as JSON
    for (String path : List.of("/api/nope", "/api/agents/nope/nope/nope/nope",
        "/api/mcp-servers/nope/logs")) {
      MvcResult result = mvc.perform(get(path)).andReturn();
      assertEquals(404, result.getResponse().getStatus(), path);
      assertFalse(result.getResponse().getContentAsString().contains("angular-shell-placeholder"),
          path + " was answered with the shell");
    }
  }

  @Test
  void theBareApiPrefixFallsToTheShellWhichIsHarmlessButWorthKnowing() {
    // pinned as observed rather than asserted as desirable: '/api/' with a trailing slash is the
    // one API-looking path the fallback does answer with HTML. No client requests it — every real
    // call names a resource — and the paths that matter 404 above. If that ever stops being true,
    // this failing is the notice.
    assertDoesNotThrow(() -> {
      MvcResult result = mvc.perform(get("/api/")).andReturn();
      assertEquals(200, result.getResponse().getStatus());
      assertTrue(result.getResponse().getContentAsString().contains("angular-shell-placeholder"));
    });
  }

  @Test
  void healthAndTheRuntimeConfigAreServedByControllersNotTheShell() throws Exception {
    // the launcher polls /health, and config.js is generated per deployment by a controller —
    // which is exactly why the SPA fallback excludes that path. Shadowing either with index.html
    // would leave the launcher waiting forever and the frontend without its runtime config.
    MvcResult health = mvc.perform(get("/health")).andReturn();
    assertEquals(200, health.getResponse().getStatus());
    assertTrue(health.getResponse().getContentAsString().contains("\"status\":\"ok\""));

    MvcResult config = mvc.perform(get("/config.js")).andReturn();
    assertEquals(200, config.getResponse().getStatus());
    assertTrue(config.getResponse().getContentAsString().contains("window.__MC_CONFIG__"),
        config.getResponse().getContentAsString());
    assertFalse(config.getResponse().getContentAsString().contains("angular-shell-placeholder"));
  }

  // ── CORS still admits the dev origin ────────────────────────────────────

  @Test
  void theDevServerOriginIsStillAllowedThroughPreflight() throws Exception {
    // ng serve runs on its own origin; losing this makes the whole dashboard fail to load in
    // development while the combined image keeps working
    MvcResult preflight = mvc.perform(options("/api/hosts")
        .header("Origin", "http://localhost:4300")
        .header("Access-Control-Request-Method", "POST")).andReturn();

    assertEquals(200, preflight.getResponse().getStatus());
    assertEquals("http://localhost:4300",
        preflight.getResponse().getHeader("Access-Control-Allow-Origin"));
  }

  @Test
  void anUnknownOriginIsRefusedPreflight() throws Exception {
    MvcResult preflight = mvc.perform(options("/api/hosts")
        .header("Origin", "https://evil.test")
        .header("Access-Control-Request-Method", "POST")).andReturn();

    assertEquals(403, preflight.getResponse().getStatus());
    assertFalse(preflight.getResponse().containsHeader("Access-Control-Allow-Origin"));
  }

  // ── the terminal's origin guard is attached to its endpoint ─────────────

  @Test
  void aCrossSiteTerminalHandshakeIsRefusedByTheRunningServer() throws Exception {
    // the endpoint hands out an interactive shell and there is no authentication anywhere in
    // this application, so the guard being wired to it is the whole of the access control.
    // TerminalOriginGuardTest proves the rule; this proves it is installed.
    WebSocketHttpHeaders crossSite = new WebSocketHttpHeaders();
    crossSite.setOrigin("https://evil.test");

    ExecutionException refused = assertThrows(ExecutionException.class,
        () -> new StandardWebSocketClient()
            .execute(new AbstractWebSocketHandler() { }, crossSite, terminalUri())
            .get(10, TimeUnit.SECONDS));

    assertTrue(String.valueOf(refused.getCause()).contains("403")
            || String.valueOf(refused.getCause()).toLowerCase().contains("forbidden"),
        "expected a forbidden handshake, got " + refused.getCause());
  }

  @Test
  void aSameOriginTerminalHandshakeIsAdmitted() throws Exception {
    // the guard must not be so strict that the dashboard's own terminal cannot connect; the
    // session is closed immediately afterwards because no hostId was supplied
    WebSocketHttpHeaders sameOrigin = new WebSocketHttpHeaders();
    sameOrigin.setOrigin("http://localhost:" + port);

    var session = new StandardWebSocketClient()
        .execute(new AbstractWebSocketHandler() { }, sameOrigin, terminalUri())
        .get(10, TimeUnit.SECONDS);

    assertTrue(session != null, "a same-origin handshake must be admitted");
    session.close();
  }


  // ── fixtures ────────────────────────────────────────────────────────────

  private record Route(HttpMethod method, String uri) implements Comparable<Route> {
    @Override
    public String toString() {
      return method + " " + uri;
    }

    @Override
    public int compareTo(Route other) {
      return toString().compareTo(other.toString());
    }
  }

  /**
   * Every mapped POST/PUT/PATCH/DELETE, with its path variables filled in. The values name
   * nothing that exists — the test profile seeds no hosts — so a route that gets past validation
   * still resolves to a 404 or a 503 rather than touching a daemon.
   */
  private Set<Route> mutatingRoutes() {
    Set<Route> routes = new TreeSet<>();
    mappings.getHandlerMethods().keySet().forEach(info -> {
      var patterns = info.getPathPatternsCondition() == null
          ? Set.<String>of() : info.getPathPatternsCondition().getPatternValues();
      for (String pattern : patterns) {
        if (!pattern.startsWith("/api/")) continue;
        for (var method : info.getMethodsCondition().getMethods()) {
          HttpMethod http = HttpMethod.valueOf(method.name());
          if (http == HttpMethod.GET || http == HttpMethod.HEAD || http == HttpMethod.OPTIONS) continue;
          routes.add(new Route(http, fillPathVariables(pattern)));
        }
      }
    });
    return routes;
  }

  private static String fillPathVariables(String pattern) {
    return pattern
        .replace("{hostId}", HOST)
        .replaceAll("\\{[^}]+}", "does-not-exist");
  }

  private URI terminalUri() {
    return URI.create("ws://localhost:" + port + "/ws/terminal");
  }

  private static String failureOf(MvcResult result) {
    Exception thrown = (Exception) result.getResolvedException();
    if (thrown != null) return thrown.getClass().getSimpleName() + ": " + thrown.getMessage();
    return result.getResponse().getStatus() + " with no resolved exception";
  }
}
