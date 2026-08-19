package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.secrets.SecretsAtRest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The secrets a reusable template carries — template-owned API keys and the MCP snapshot
 * values captured with it — in the shapes this package stores them in.
 *
 * <p>Not the trust boundary itself: the rules a stored secret obeys live in
 * {@link SecretsAtRest}, which {@code mcp/McpConfigStore} shares. This package implemented all
 * four of them separately once, and they had drifted — a blank submission for a secret with no
 * stored value was dropped here and refused there, and the re-encryption these snapshots
 * documented as "matching the behavior of template-owned API keys" was not what that path
 * actually did.
 *
 * <p>What remains here is the template's own shape: a {@link TemplateMcpConfigValue} list per
 * snapshot, and what one looks like once redacted for the API.
 */
@Component
class TemplateSecrets {

  private final SecretsAtRest secrets;

  TemplateSecrets(SecretsAtRest secrets) {
    this.secrets = secrets;
  }

  // ── one value ──────────────────────────────────────────────────────────────

  String encrypt(String clear) {
    return secrets.seal(clear);
  }

  /**
   * The envelope to store for a submitted secret, keeping {@code prior} when the editor sent
   * a blank — which it does for every secret it did not touch, having never received the
   * ciphertext to send back.
   */
  String encryptOrKeep(String submitted, String prior, String key) {
    return secrets.sealOrKeep(submitted, prior, key);
  }

  /** The stored value in the clear, or null when this key can no longer recover it. */
  String decryptOrNull(String encrypted) {
    return secrets.openOrNull(encrypted);
  }

  /** Whether a stored envelope still opens — what the API reports as {@code recoverable}. */
  boolean isRecoverable(String encrypted) {
    return secrets.isRecoverable(encrypted);
  }

  // ── a snapshot's value list ────────────────────────────────────────────────

  List<TemplateMcpConfigValue> encryptValues(Map<String, String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .map(entry -> new TemplateMcpConfigValue(entry.getKey(), secrets.seal(entry.getValue())))
        .toList();
  }

  /**
   * The values a deploy can actually use. An unrecoverable one is dropped rather than failing
   * the deploy: the rest of the template still applies, minus that credential.
   */
  Map<String, String> decryptValues(List<TemplateMcpConfigValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TemplateMcpConfigValue value : stream(values).toList()) {
      if (value.key() == null || value.key().isBlank()) continue;
      String clear = secrets.openOrNull(value.encryptedValue());
      if (clear != null) result.put(value.key(), clear);
    }
    return result;
  }

  /** A normal template save is also the key-rotation opportunity for MCP snapshot values,
   *  matching what a template-owned API key now does on the same save. */
  List<TemplateMcpConfigValue> reencryptValues(List<TemplateMcpConfigValue> values) {
    return stream(values)
        .map(value -> new TemplateMcpConfigValue(
            value.key(), secrets.reseal(value.encryptedValue())))
        .toList();
  }

  // ── redaction ──────────────────────────────────────────────────────────────

  /** An MCP snapshot as the API exposes it: keys visible, every value withheld. */
  static McpServerSpec redacted(McpServerSpec value) {
    return new McpServerSpec(
        value.name(), value.transport(), value.url(), value.command(), value.args(), value.enabled(),
        null, redactValues(value.environment()), redactValues(value.headers()));
  }

  private static List<TemplateMcpConfigValue> redactValues(List<TemplateMcpConfigValue> values) {
    return stream(values).map(value -> new TemplateMcpConfigValue(value.key(), null)).toList();
  }

  private static Stream<TemplateMcpConfigValue> stream(List<TemplateMcpConfigValue> values) {
    return (values == null ? List.<TemplateMcpConfigValue>of() : values).stream()
        .filter(Objects::nonNull);
  }
}
