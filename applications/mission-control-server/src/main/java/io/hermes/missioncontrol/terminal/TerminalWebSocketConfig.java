package io.hermes.missioncontrol.terminal;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class TerminalWebSocketConfig implements WebSocketConfigurer {

  /**
   * The {@code ng serve} origins, and deliberately NOT WebConfig's configurable list.
   *
   * <p>These are the only two exemptions from the same-origin check below. WebConfig's
   * allowlist is deployment configuration and can be widened; this endpoint hands out an
   * interactive shell, so widening it by editing an env var must not be possible. Everything
   * else is admitted only by matching the Host header, which covers every real deployment —
   * including the tailscale serve proxy — without being configured at all.
   */
  private static final Set<String> DEV_ORIGINS =
      Set.of("http://localhost:4200", "http://localhost:4300");

  private final TerminalSocketHandler terminalHandler;

  public TerminalWebSocketConfig(TerminalSocketHandler terminalHandler) {
    this.terminalHandler = terminalHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(terminalHandler, "/ws/terminal")
        // the interceptor below enforces same-origin-or-dev; a terminal is
        // remote code execution, so cross-site WebSocket hijacking must fail
        .setAllowedOriginPatterns("*")
        .addInterceptors(originGuard());
  }

  /**
   * Package-private rather than private so the guard can be exercised directly. It is the
   * only access control on an endpoint that hands out an interactive shell, and a
   * cross-site handshake it wrongly admits is remote code execution.
   */
  static HandshakeInterceptor originGuard() {
    return new HandshakeInterceptor() {
      @Override
      public boolean beforeHandshake(@NonNull ServerHttpRequest request,
          @NonNull ServerHttpResponse response, @NonNull WebSocketHandler handler,
          @NonNull Map<String, Object> attributes) {
        String origin = request.getHeaders().getOrigin();
        if (origin == null || DEV_ORIGINS.contains(origin)) return true;
        InetSocketAddress host = request.getHeaders().getHost();
        URI o = URI.create(origin);
        int originPort = o.getPort() != -1 ? o.getPort() : ("https".equals(o.getScheme()) ? 443 : 80);
        int hostPort = host == null ? -1 : host.getPort();
        if (hostPort == 0) {
          // no explicit port in the Host header (e.g. behind the tailscale
          // serve proxy) — infer the default from the forwarded scheme
          String proto = request.getHeaders().getFirst("X-Forwarded-Proto");
          String scheme = proto != null ? proto : request.getURI().getScheme();
          hostPort = "https".equalsIgnoreCase(scheme) ? 443 : 80;
        }
        boolean sameOrigin = host != null && o.getHost() != null
            && o.getHost().equalsIgnoreCase(host.getHostString())
            && originPort == hostPort;
        if (!sameOrigin) response.setStatusCode(HttpStatus.FORBIDDEN);
        return sameOrigin;
      }

      @Override
      public void afterHandshake(@NonNull ServerHttpRequest request,
          @NonNull ServerHttpResponse response, @NonNull WebSocketHandler handler,
          Exception exception) { }
    };
  }
}
