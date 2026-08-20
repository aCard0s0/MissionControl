package io.hermes.missioncontrol.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
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

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // dev only: ng serve runs on its own origin; the combined image is same-origin
    registry.addMapping("/**")
        .allowedOrigins("http://localhost:4200", "http://localhost:4300")
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE");
  }
}
