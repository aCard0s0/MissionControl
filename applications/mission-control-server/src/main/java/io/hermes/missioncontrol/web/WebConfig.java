package io.hermes.missioncontrol.web;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
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
        .addResolver(new PathResourceResolver() {
          @Override
          protected Resource getResource(@NonNull String path, @NonNull Resource location) throws IOException {
            Resource resource = location.createRelative(path);
            if (resource.exists() && resource.isReadable()) return resource;
            if (path.startsWith("api/") || path.equals("health") || path.equals("config.js")) return null;
            return new ClassPathResource("/static/index.html");
          }
        });
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    // dev only: ng serve runs on its own origin; the combined image is same-origin
    registry.addMapping("/**")
        .allowedOrigins("http://localhost:4200", "http://localhost:4300")
        .allowedMethods("GET", "POST", "PATCH", "DELETE");
  }
}
