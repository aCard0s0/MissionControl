package io.hermes.missioncontrol.web;

import io.hermes.missioncontrol.config.AppProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the frontend runtime config dynamically — replaces the old nginx
 * entrypoint script. One image, any environment, no rebuild.
 */
@RestController
public class RuntimeConfigController {

  private final AppProperties props;

  public RuntimeConfigController(AppProperties props) {
    this.props = props;
  }

  @GetMapping(value = "/config.js", produces = "text/javascript")
  public ResponseEntity<String> configJs() {
    String js = """
        window.__MC_CONFIG__ = {
          apiBaseUrl: '%s',
          dockerSocket: '%s',
        };
        """.formatted(escape(props.apiBaseUrl()), escape(props.dockerSocket()));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType("text/javascript"))
        .body(js);
  }

  /** Escape for a single-quoted JS string literal: \  '  </  and newlines. */
  static String escape(String value) {
    if (value == null) return "";
    return value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("</", "<\\/")
        .replace("\n", "")
        .replace("\r", "");
  }
}
