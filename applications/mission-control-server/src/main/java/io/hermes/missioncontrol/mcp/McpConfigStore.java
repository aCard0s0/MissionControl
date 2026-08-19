package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretCipher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The stored form of an MCP definition: the JSON envelope in {@code config_json} and the
 * encrypted values inside it.
 *
 * <p>Split out of {@link McpRegistryService} because this is the trust boundary. A secret
 * only ever leaves here decrypted for a render or a connection; a caller asking for a DTO
 * gets it redacted; and an envelope this key can no longer open is preserved rather than
 * destroyed, so editing an unrelated field never loses it.
 */
@Component
class McpConfigStore {

  private final SecretCipher cipher;
  private final ObjectMapper json;

  McpConfigStore(SecretCipher cipher, ObjectMapper json) {
    this.cipher = cipher;
    this.json = json;
  }

  // ── envelope ───────────────────────────────────────────────────────────────

  StoredConfig read(ServerRow row) {
    try {
      StoredConfig value = json.readValue(row.configJson(), StoredConfig.class);
      // All collections are written non-null. Defend against early/development rows.
      return new StoredConfig(value.transport(), value.url(), value.image(), value.platform(),
          list(value.entrypoint()), list(value.command()), value.stdioCommand(), list(value.args()),
          value.internalPort(), value.publishedPort(), value.path(), value.crossHostUrl(),
          list(value.environment()), list(value.headers()), list(value.volumes()), value.healthcheck(),
          list(value.supportServices()));
    } catch (Exception e) {
      throw new ResourceConflictException("stored MCP configuration is unreadable", e);
    }
  }

  String write(StoredConfig config) {
    try {
      return json.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("could not store MCP configuration", e);
    }
  }

  /** Encrypts a validated request into its stored form, carrying over secrets the request
   *  left blank — which is how the UI says "keep the value you already hold". */
  StoredConfig store(Validated value, StoredConfig existing) {
    List<StoredValue> environment =
        storeValues(value.environment(), existing == null ? List.of() : existing.environment());
    List<StoredValue> headers =
        storeValues(value.headers(), existing == null ? List.of() : existing.headers());
    Map<String, StoredSupportService> previousSupports = new LinkedHashMap<>();
    if (existing != null) {
      for (StoredSupportService support : existing.supportServices()) {
        previousSupports.put(support.name(), support);
      }
    }
    List<StoredSupportService> supports = new ArrayList<>();
    for (SupportServiceRequest support : value.supportServices()) {
      StoredSupportService previous = previousSupports.get(support.name());
      supports.add(new StoredSupportService(support.name(), support.image(), support.platform(),
          support.entrypoint(), support.command(),
          storeValues(support.environment(), previous == null ? List.of() : previous.environment()),
          support.volumes(), support.healthcheck()));
    }
    return new StoredConfig(value.transport(), value.url(), value.image(), value.platform(),
        value.entrypoint(), value.command(), value.stdioCommand(), value.args(), value.internalPort(),
        value.publishedPort(), value.path(), value.crossHostUrl(), environment, headers, value.volumes(),
        value.healthcheck(), List.copyOf(supports));
  }

  private List<StoredValue> storeValues(List<ConfigValueInput> input, List<StoredValue> existing) {
    Map<String, StoredValue> previous = new LinkedHashMap<>();
    for (StoredValue value : existing) previous.put(value.key(), value);
    List<StoredValue> result = new ArrayList<>();
    for (ConfigValueInput item : input) {
      if (item.shouldClear()) continue;
      String stored;
      if (!item.secret()) {
        stored = item.value() == null ? "" : item.value();
      } else if (item.value() != null && !item.value().isBlank()) {
        stored = cipher.encrypt(item.value());
      } else {
        StoredValue old = previous.get(item.key());
        if (old == null || !old.secret()) {
          throw new IllegalArgumentException("secret value is required: " + item.key());
        }
        stored = rotateIfRecoverable(old.value());
      }
      result.add(new StoredValue(item.key(), stored, item.secret()));
    }
    return List.copyOf(result);
  }

  private String rotateIfRecoverable(String stored) {
    if (stored == null) return null;
    try {
      return cipher.encrypt(cipher.decrypt(stored));
    } catch (RuntimeException unrecoverable) {
      // Preserve the opaque envelope so editing unrelated fields never destroys
      // it. DTO recoverable=false tells the operator it must be replaced before
      // the definition can be applied or connected.
      return stored;
    }
  }

  // ── decryption ─────────────────────────────────────────────────────────────

  Map<String, String> materialize(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      result.put(value.key(), value.value() == null ? ""
          : value.secret() ? cipher.decrypt(value.value()) : value.value());
    }
    return Map.copyOf(result);
  }

  /** Rendering one host must not let an unrelated server's stale encryption
   * key block lifecycle operations. The target is checked strictly before an
   * apply/start; non-target unrecoverable substitutions remain blank. */
  Map<String, String> materializeForRender(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      try {
        result.put(value.key(), value.value() == null ? ""
            : value.secret() ? cipher.decrypt(value.value()) : value.value());
      } catch (RuntimeException unrecoverable) {
        result.put(value.key(), "");
      }
    }
    return Map.copyOf(result);
  }

  void assertRecoverable(StoredConfig config) {
    assertRecoverable(config.environment());
    for (StoredSupportService support : config.supportServices()) {
      assertRecoverable(support.environment());
    }
  }

  private void assertRecoverable(List<StoredValue> values) {
    for (StoredValue value : values) {
      if (!value.secret()) continue;
      if (value.value() == null) {
        throw new ResourceConflictException("secret value is not set: " + value.key());
      }
      try {
        cipher.decrypt(value.value());
      } catch (RuntimeException error) {
        throw new ResourceConflictException("secret value is unrecoverable: " + value.key(), error);
      }
    }
  }

  /** Values as the API exposes them: a secret is reported as set/recoverable, never returned. */
  List<ConfigValueDto> redact(List<StoredValue> values) {
    return values.stream().map(value -> {
      if (!value.secret()) return new ConfigValueDto(value.key(), value.value(), false, true, true);
      boolean set = value.value() != null;
      boolean recoverable = false;
      if (set) {
        try {
          cipher.decrypt(value.value());
          recoverable = true;
        } catch (RuntimeException ignored) { }
      }
      return new ConfigValueDto(value.key(), null, true, set, recoverable);
    }).toList();
  }

  private static <T> List<T> list(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
