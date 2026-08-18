package io.hermes.missioncontrol.terminal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * The handshake origin check on {@code /ws/terminal}.
 *
 * <p>The endpoint hands the client an interactive shell inside a container, and there is no
 * authentication anywhere in this application — so this guard is the whole of the access
 * control, and a cross-site handshake it admits is remote code execution. Spring's own
 * origin check is switched off ({@code setAllowedOriginPatterns("*")}), which makes it the
 * single point of failure as well.
 */
class TerminalOriginGuardTest {

  private final HandshakeInterceptor guard = TerminalWebSocketConfig.originGuard();

  /** Runs the guard for one Origin/Host pair, returning whether the handshake proceeds. */
  private boolean handshake(String origin, String host, String forwardedProto,
      ServerHttpResponse response) throws Exception {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    HttpHeaders headers = new HttpHeaders();
    if (origin != null) headers.setOrigin(origin);
    if (host != null) {
      URI parsed = URI.create("http://" + host);
      headers.setHost(InetSocketAddress.createUnresolved(
          parsed.getHost(), parsed.getPort() == -1 ? 0 : parsed.getPort()));
    }
    if (forwardedProto != null) headers.set("X-Forwarded-Proto", forwardedProto);
    when(request.getHeaders()).thenReturn(headers);
    when(request.getURI()).thenReturn(URI.create("http://" + (host == null ? "x" : host) + "/ws/terminal"));

    return guard.beforeHandshake(
        request, response, mock(WebSocketHandler.class), new HashMap<>());
  }

  private boolean handshake(String origin, String host) throws Exception {
    return handshake(origin, host, null, mock(ServerHttpResponse.class));
  }

  @Test
  void aSameOriginHandshakeIsAllowed() throws Exception {
    assertTrue(handshake("http://mc.example:8080", "mc.example:8080"));
  }

  @Test
  void aCrossSiteOriginIsForbiddenAndSaysSo() throws Exception {
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    assertFalse(handshake("https://evil.example", "mc.example:8080", null, response));

    // the browser must see a 403, not a dropped connection it might retry
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void aDifferentHostnameOnAMatchingPortIsForbidden() throws Exception {
    assertFalse(handshake("http://evil.example:8080", "mc.example:8080"));
  }

  @Test
  void aMatchingHostnameOnADifferentPortIsForbidden() throws Exception {
    // a different port on the same hostname is a different origin — commonly another
    // service the user is also running on localhost
    assertFalse(handshake("http://mc.example:9999", "mc.example:8080"));
  }

  @Test
  void theHostnameComparisonIsCaseInsensitive() throws Exception {
    assertTrue(handshake("http://MC.Example:8080", "mc.example:8080"));
  }

  @Test
  void theDevOriginsAreAllowedRegardlessOfHost() throws Exception {
    // KNOWN GAP: these are unconditional, so they are accepted in a production image too.
    // Narrowing them to a dev profile is a deployment decision, not a test-coverage one.
    assertTrue(handshake("http://localhost:4200", "mc.example:8080"));
    assertTrue(handshake("http://localhost:4300", "mc.example:8080"));
    // a neighbouring dev port is not on the list
    assertFalse(handshake("http://localhost:4400", "mc.example:8080"));
  }

  @Test
  void aMissingOriginIsAllowedBecauseNonBrowserClientsSendNone() throws Exception {
    // deliberate: the CLI and health probes send no Origin. Browsers always do, and it is
    // browsers that can be made to open a cross-site socket.
    ServerHttpResponse response = mock(ServerHttpResponse.class);

    assertTrue(handshake(null, "mc.example:8080", null, response));

    verify(response, never()).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void anOriginWithNoExplicitPortInfersTheSchemeDefault() throws Exception {
    // behind a proxy the Host header carries no port; the forwarded scheme decides
    // whether the origin's implicit port is 443 or 80
    assertTrue(handshake("https://mc.example", "mc.example", "https",
        mock(ServerHttpResponse.class)));
    assertTrue(handshake("http://mc.example", "mc.example", "http",
        mock(ServerHttpResponse.class)));
    // https origin against a plain-http proxy is a genuine mismatch
    assertFalse(handshake("https://mc.example", "mc.example", "http",
        mock(ServerHttpResponse.class)));
  }

  @Test
  void aClientSuppliedForwardedProtoIsCurrentlyTrusted() throws Exception {
    // KNOWN GAP: X-Forwarded-Proto is read straight off the request, so a client can flip
    // the inferred host port between 80 and 443 and satisfy the comparison itself. Fixing
    // it means deciding which proxies to trust — a deployment decision this pass does not
    // make. Pinned as current behaviour so the change is visible when it happens.
    assertTrue(handshake("https://mc.example", "mc.example", "https",
        mock(ServerHttpResponse.class)));
  }

  @Test
  void anOriginWithNoHostToCompareIsForbidden() throws Exception {
    // a browser sends the literal "null" for a file:// or sandboxed document, and a URL with an
    // empty authority parses but names no host — neither can be same-origin with anything
    assertFalse(handshake("null", "mc.test:8080", null, mock(ServerHttpResponse.class)));
    assertFalse(handshake("https:///nohost", "mc.test:8080", null, mock(ServerHttpResponse.class)));
  }

  @Test
  void aHandshakeWithNoHostHeaderIsForbidden() throws Exception {
    // nothing to compare the origin against; admitting it would trust the client's own claim
    assertFalse(handshake("http://evil.test", null, null, mock(ServerHttpResponse.class)));
  }
}
