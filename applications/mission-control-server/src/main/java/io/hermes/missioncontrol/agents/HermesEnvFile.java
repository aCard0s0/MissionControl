package io.hermes.missioncontrol.agents;

import io.hermes.missioncontrol.agents.api.EnvEntry;
import io.hermes.missioncontrol.docker.DockerHostRef;
import io.hermes.missioncontrol.secrets.Secrets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The profile's {@code .env}: where every provider API key lives, and the only thing in this
 * package that knows what that file looks like.
 *
 * <p>Split out of {@link HermesProfiles} because these are the writes that must never
 * reach a log. Key and value are always positional arguments to the script, never
 * interpolated into it, and the write goes through the sensitive exec seam.
 *
 * <p>Reading and validating live here too, and did not always. {@code HermesSetup} carried
 * its own line parser and its own key/value rules, and {@link #maskApiKey} carried a third
 * reading of the same format — so what counted as a set variable depended on which screen
 * asked. Worse, the value rule {@link #assertWritable} enforces was checked by
 * {@code HermesSetup.putEnv} and by nothing else, while {@code HermesModelConfig} reached
 * {@link #write} directly with an API key straight off a create-agent request: a newline in
 * that field wrote a second {@code .env} line that {@link #remove}'s {@code grep -v} could
 * never match, and so could never be deleted.
 */
@Component
class HermesEnvFile {

  private static final Pattern KEY = Pattern.compile(EnvEntry.KEY_PATTERN);

  private final HermesContainerFiles files;

  HermesEnvFile(HermesContainerFiles files) {
    this.files = files;
  }

  private String envPath(DockerHostRef host, String containerId, String name) {
    return files.requireProfileDir(host, containerId, name) + "/.env";
  }

  /**
   * The rule every write into a profile {@code .env} obeys, wherever it came from.
   *
   * <p>The value is written as a whole {@code .env} line. A newline in it appends further
   * lines that {@link #remove} can never match and therefore never delete — including a second
   * definition of a key the request appears not to touch.
   */
  static void assertWritable(String key, String value) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("invalid env key: " + key);
    }
    if (value != null
        && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException(
          "env values must not contain NUL or line breaks: " + key);
    }
  }

  void write(DockerHostRef host, String containerId, String name, String key, String value) {
    assertWritable(key, value);
    String path = envPath(host, containerId, name);
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\"; value=\"$3\";",
        "touch \"$path\";",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "printf '%s=%s\\n' \"$key\" \"$value\" >> \"$path.tmp\";",
        "mv \"$path.tmp\" \"$path\";");
    files.execSensitive(
        host, containerId, List.of("sh", "-lc", script, "_", path, key, value),
        "write profile environment");
  }

  void remove(DockerHostRef host, String containerId, String name, String key) {
    assertWritable(key, null);
    String path = envPath(host, containerId, name);
    String script = String.join(" ",
        "path=\"$1\"; key=\"$2\";",
        "[ -f \"$path\" ] || exit 0;",
        "grep -v \"^${key}=\" \"$path\" > \"$path.tmp\" || true;",
        "mv \"$path.tmp\" \"$path\";");
    files.exec(host, containerId, List.of("sh", "-lc", script, "_", path, key));
  }

  /** Writes the documented commented-out .env template; no-op when .env exists. */
  void seedIfMissing(DockerHostRef host, String containerId, String name) {
    String path = envPath(host, containerId, name);
    if (files.fileExists(host, containerId, path)) return;
    files.writeFile(host, containerId, path, HermesEnvCatalog.template());
  }

  /**
   * The file's contents as variables. Comments and blank lines are skipped, the key is the
   * text before the first {@code =}, and both halves are trimmed — a leading space in a
   * hand-edited file does not hide a variable from one screen and not another.
   */
  static Map<String, String> parse(String env) {
    Map<String, String> values = new HashMap<>();
    if (env == null || env.isBlank()) return values;
    for (String line : env.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
      int eq = trimmed.indexOf('=');
      if (eq <= 0) continue;
      values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
    }
    return values;
  }

  /** The provider's stored key, masked for display, or "" when it carries none. */
  static String maskApiKey(String env, String provider) {
    String key = ModelProviderRegistry.envVar(ModelProviderRegistry.normalizeKey(provider));
    if (key == null) return "";
    String value = parse(env).get(key);
    return value == null ? "" : Secrets.mask(value);
  }
}
