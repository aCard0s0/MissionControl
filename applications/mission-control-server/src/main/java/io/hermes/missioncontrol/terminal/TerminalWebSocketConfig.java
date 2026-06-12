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

  /** dev only — mirrors the CORS allowlist in WebConfig; the combined image is same-origin */
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

  private static HandshakeInterceptor originGuard() {
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
        boolean sameOrigin = host != null && o.getHost() != null
            && o.getHost().equalsIgnoreCase(host.getHostString())
            && originPort == host.getPort();
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
