package io.hermes.missioncontrol.agents.templates;

import io.hermes.missioncontrol.secrets.SecretCipher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The secrets a reusable template carries: template-owned API keys and the MCP snapshot
 * values captured with it.
 *
 * <p>Split out of {@link ProfileTemplateService} because this is its trust boundary. A value
 * is decrypted only on the way into a container, is never returned to a caller, and an
 * envelope this key can no longer open degrades to "unrecoverable" rather than failing a
 * whole read or deploy — a loss the operator has to be able to see and act on.
 */
@Component
class TemplateSecrets {

  private static final Logger log = LoggerFactory.getLogger(TemplateSecrets.class);

  private final SecretCipher cipher;

  TemplateSecrets(SecretCipher cipher) {
    this.cipher = cipher;
  }

  String encrypt(String clear) {
    return cipher.encrypt(clear);
  }

  /**
   * The stored value in the clear, or null when it cannot be recovered.
   *
   * <p>A wrong MC_SECRET_KEY or corrupt ciphertext makes one secret unrecoverable; failing
   * the read or deploy over it would take the rest of the template with it, so the loss is
   * logged and reported through {@code recoverable=false} instead.
   */
  String decryptOrNull(String encrypted) {
    if (encrypted == null) return null;
    try {
      return cipher.decrypt(encrypted);
    } catch (RuntimeException e) {
      log.warn("failed to decrypt a stored template secret (check MC_SECRET_KEY): {}", e.getMessage());
      return null;
    }
  }

  List<TemplateMcpConfigValue> encryptValues(Map<String, String> values) {
    if (values == null || values.isEmpty()) return List.of();
    return values.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .map(entry -> new TemplateMcpConfigValue(entry.getKey(), cipher.encrypt(entry.getValue())))
        .toList();
  }

  Map<String, String> decryptValues(List<TemplateMcpConfigValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (TemplateMcpConfigValue value : values == null ? List.<TemplateMcpConfigValue>of() : values) {
      if (value == null || value.key() == null || value.key().isBlank()) continue;
      String clear = decryptOrNull(value.encryptedValue());
      if (clear != null) result.put(value.key(), clear);
    }
    return result;
  }

  /** A normal template save is also the key-rotation opportunity for MCP
   * snapshot values, matching the behavior of template-owned API keys. */
  List<TemplateMcpConfigValue> reencryptValues(List<TemplateMcpConfigValue> values) {
    return stream(values)
        .map(value -> {
          String clear = decryptOrNull(value.encryptedValue());
          return clear == null
              ? value
              : new TemplateMcpConfigValue(value.key(), cipher.encrypt(clear));
        })
        .toList();
  }

  /** An MCP snapshot as the API exposes it: keys visible, every value withheld. */
  static McpServerSpec redacted(McpServerSpec value) {
    return new McpServerSpec(
        value.name(), value.transport(), value.url(), value.command(), value.args(), value.enabled(),
        null, redactValues(value.environment()), redactValues(value.headers()));
  }

  private static List<TemplateMcpConfigValue> redactValues(List<TemplateMcpConfigValue> values) {
    return stream(values).map(value -> new TemplateMcpConfigValue(value.key(), null)).toList();
  }

  private static java.util.stream.Stream<TemplateMcpConfigValue> stream(
      List<TemplateMcpConfigValue> values) {
    return (values == null ? List.<TemplateMcpConfigValue>of() : values).stream()
        .filter(Objects::nonNull);
  }
}
