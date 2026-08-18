package io.hermes.missioncontrol.agents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Lenient readers for the untyped maps SnakeYAML hands back from a profile's
 * {@code config.yaml} and a skill's frontmatter.
 *
 * <p>Every accessor here degrades rather than throws: a config written by a newer
 * hermes, or hand-edited into an unexpected shape, must still render a profile in
 * the dashboard instead of 500-ing the whole agents view.
 */
final class YamlValues {

  private static final Yaml YAML = new Yaml();

  private YamlValues() {}

  /** The document as a mapping, or an empty map for anything else (including a parse failure). */
  static Map<?, ?> parseMap(String yamlText) {
    if (yamlText == null || yamlText.isBlank()) return Map.of();
    try {
      Object loaded = YAML.load(yamlText);
      return loaded instanceof Map<?, ?> map ? map : Map.of();
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  /** As {@link #parseMap}, but a non-mapping document is an error rather than an empty map. */
  static void requireMapping(String yamlText, String message) {
    if (yamlText == null || yamlText.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    try {
      if (!(YAML.load(yamlText) instanceof Map<?, ?>)) {
        throw new IllegalArgumentException(message);
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException(message, e);
    }
  }

  static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  static List<Object> asMutableList(Object node) {
    return node instanceof List<?> list ? new ArrayList<>(list) : new ArrayList<>();
  }

  /** Joins a YAML args list back into a space-separated string for the edit form. */
  static String joinArgs(Object node) {
    if (node instanceof List<?> list) {
      List<String> parts = new ArrayList<>();
      for (Object v : list) {
        String s = stringValue(v);
        if (!s.isBlank()) parts.add(s);
      }
      return String.join(" ", parts);
    }
    return stringValue(node);
  }

  static double toDouble(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    try {
      return value == null ? 0 : Double.parseDouble(String.valueOf(value).trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Dumps an edited config tree back to YAML text. */
  static String dump(Object root) {
    return YAML.dump(root);
  }
}
