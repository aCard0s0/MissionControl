package io.hermes.missioncontrol.web;

import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.hosts.HostService;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.format.FormatterRegistry;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular build (copied into classpath:/static at image build time)
 * with an SPA fallback: unknown non-API paths resolve to index.html so deep
 * links like /agents/a-1 work on refresh.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final List<String> corsOrigins;
  private final HostService hosts;

  public WebConfig(@Value("${mc.cors-origins}") List<String> corsOrigins, HostService hosts) {
    this.corsOrigins = corsOrigins;
    this.hosts = hosts;
  }

  /**
   * A {@code hostId} in a path or a query parameter binds straight to the resolved, probed
   * {@link DockerHostRef}.
   *
   * <p>Fifty-three handlers opened with the same line — {@code DockerHostRef host =
   * hosts.requireConnected(hostId)} — and eleven controllers held a {@link HostService} to
   * write it. Declaring the parameter as what the handler actually wants says the same thing
   * once.
   *
   * <p>{@link HostService#requireConnected} rather than {@code ref}: everything reached through
   * a URL is answering a request, and so has a caller to report an unreachable daemon to as a
   * 503. Background work resolves through {@code ref} and does not come through here.
   *
   * <p>Two consequences worth knowing. The refusal now happens during argument binding, so it
   * precedes {@code @Valid} on a request body: a malformed body sent to a route whose host is
   * down answers 503 rather than 400. And it arrives at the advice wrapped in Spring's
   * conversion failure, which {@code ApiExceptionHandler.unreadableRequest} unwraps — without
   * that, every one of these would answer 400.
   */
  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, DockerHostRef.class, hosts::requireConnected);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(spaFallbackResolver());
  }

  /**
   * Resolves an unknown non-API path to {@code index.html} so a deep link survives a refresh.
   *
   * <p>Package-private rather than inline for the same reason as
   * {@code TerminalWebSocketConfig.originGuard}: it decides which requests are handed the SPA
   * shell and which fall through to a 404, and getting that wrong turns an API typo into an
   * HTML page the client cannot parse.
   */
  static PathResourceResolver spaFallbackResolver() {
    return new PathResourceResolver() {
      @Override
      protected Resource getResource(@NonNull String path, @NonNull Resource location) throws IOException {
        return resolveSpaPath(path, location);
      }
    };
  }

  /**
   * The rule itself, as a plain function: the resolver's own hook is protected.
   *
   * <p>The shell is resolved against {@code location} rather than named absolutely. A
   * resolver's contract is to return null when it cannot resolve, and the previous version
   * returned {@code classpath:/static/index.html} without ever asking whether that file was
   * there. {@code ResourceHttpRequestHandler} then calls {@code lastModified()} on it, which
   * throws {@link java.io.FileNotFoundException} out of the handler — so a build without the
   * Angular bundle answered every unknown path with a 500 and a 45-line stack trace at ERROR
   * instead of a 404. Returning null lets Spring raise its own {@code NoResourceFoundException},
   * which is already mapped to a clean 404 and logs nothing.
   *
   * <p>Resolving relative to the location also drops the second copy of the
   * {@code classpath:/static/} literal that {@link #addResourceHandlers} already owns.
   */
  static Resource resolveSpaPath(String path, Resource location) throws IOException {
    Resource resource = location.createRelative(path);
    if (resource.exists() && resource.isReadable()) return resource;
    if (path.startsWith("api/") || path.equals("health") || path.equals("config.js")) return null;
    Resource shell = location.createRelative("index.html");
    return shell.exists() && shell.isReadable() ? shell : null;
  }

  /**
   * The allowlist is configuration, not a dev-only constant.
   *
   * <p>This block used to hardcode the two {@code ng serve} origins on the reasoning that
   * "the combined image is same-origin, so CORS does not apply in production". That is the
   * trap. Spring's CORS processor rejects any request carrying an unlisted {@code Origin}
   * with a bare 403 before the handler runs, and per the Fetch spec a browser attaches
   * {@code Origin} to every request whose method is not GET or HEAD — <em>including
   * same-origin ones</em>. So the deployed origin was not exempt, it was simply absent:
   * navigation and GETs worked, and every POST/PUT/PATCH/DELETE 403'd. The symptom looks
   * like a broken dashboard, not like a CORS allowlist.
   *
   * <p>Note this is the opposite default from {@link
   * io.hermes.missioncontrol.terminal.TerminalWebSocketConfig#originGuard()}, which compares
   * Origin against the Host header and so admits same-origin traffic on any deployment
   * without being told about it. That endpoint hands out a shell, so it fails closed against
   * a list it does not read; this one is ordinary API surface and is configured instead.
   */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/**")
        .allowedOriginPatterns(corsOrigins.toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE");
  }
}
