package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.secrets.Secrets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The profile's {@code .env}: where every provider API key lives.
 *
 * <p>Split out of {@link HermesProfiles} because these are the writes that must never
 * reach a log. Key and value are always positional arguments to the script, never
 * interpolated into it, and the write goes through the sensitive exec seam.
 */
@Component
class HermesEnvFile {

  private final HermesContainerFiles files;

  HermesEnvFile(HermesContainerFiles files) {
    this.files = files;
  }

  private String envPath(String url, String containerId, String name) {
    return files.requireProfileDir(url, containerId, name) + "/.env";
  }

  void write(String url, String containerId, String name, String key, String value) {
    String path = envPath(url, containerId, name);
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\"; value=\"$3\";",
        "touch \"$path\";",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "printf '%s=%s\\n' \"$key\" \"$value\" >> \"$path.tmp\";",
        "mv \"$path.tmp\" \"$path\";");
    files.execSensitive(
        url, containerId, List.of("sh", "-lc", script, "_", path, key, value),
        "write profile environment");
  }

  void remove(String url, String containerId, String name, String key) {
    String path = envPath(url, containerId, name);
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\";",
        "[ -f \"$path\" ] || exit 0;",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "mv \"$path.tmp\" \"$path\";");
    files.exec(url, containerId, List.of("sh", "-lc", script, "_", path, key));
  }

  /** Writes the documented commented-out .env template; no-op when .env exists. */
  void seedIfMissing(String url, String containerId, String name) {
    String path = envPath(url, containerId, name);
    if (files.fileExists(url, containerId, path)) return;
    files.writeFile(url, containerId, path, HermesSetup.envTemplate());
  }

  /** The provider's stored key, masked for display, or "" when it carries none. */
  static String maskApiKey(String env, String provider) {
    if (env == null || env.isBlank()) return "";
    String key = ModelProviderRegistry.envVar(ModelProviderRegistry.normalizeKey(provider));
    if (key == null) return "";
    for (String line : env.split("\\R")) {
      if (line.startsWith(key + "=")) {
        return Secrets.mask(line.substring(key.length() + 1));
      }
    }
    return "";
  }
}
