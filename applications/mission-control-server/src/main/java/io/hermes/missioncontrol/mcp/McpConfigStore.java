package io.hermes.missioncontrol.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.mcp.McpServerRepository.ServerRow;
import io.hermes.missioncontrol.secrets.SecretsAtRest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The stored form of an MCP definition: the JSON envelope in {@code config_json} and the
 * encrypted values inside it.
 *
 * <p>Split out of {@link McpRegistryService} because this is the catalog's trust boundary. A
 * secret only ever leaves here decrypted for a render or a connection, and a caller asking
 * for a DTO gets it redacted.
 *
 * <p>The rules a stored secret obeys — seal on the way in, keep and re-seal on a blank
 * submission, preserve an envelope this key cannot open, degrade rather than fail a read —
 * belong to {@link SecretsAtRest}, which the template store shares. What stays here is the
 * catalog's own shape: which values are secret at all, and what a redacted one looks like on
 * the wire.
 */
@Component
class McpConfigStore {

  private final SecretsAtRest secrets;
  private final ObjectMapper json;

  McpConfigStore(SecretsAtRest secrets, ObjectMapper json) {
    this.secrets = secrets;
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
      String stored = item.secret()
          ? secrets.sealOrKeep(item.value(), priorEnvelope(previous, item.key()), item.key())
          : item.value() == null ? "" : item.value();
      result.add(new StoredValue(item.key(), stored, item.secret()));
    }
    return List.copyOf(result);
  }

  /**
   * The envelope a blank submission may carry forward, or null when there is none to keep.
   *
   * <p>A stored value that was not marked secret is not one: it is readable plaintext, and
   * promoting it to a secret's envelope would hand back something never encrypted.
   */
  private static String priorEnvelope(Map<String, StoredValue> previous, String key) {
    StoredValue old = previous.get(key);
    return old == null || !old.secret() ? null : old.value();
  }

  // ── decryption ─────────────────────────────────────────────────────────────

  Map<String, String> materialize(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      result.put(value.key(), value.value() == null ? ""
          : value.secret() ? secrets.open(value.value()) : value.value());
    }
    return Map.copyOf(result);
  }

  /** Rendering one host must not let an unrelated server's stale encryption
   * key block lifecycle operations. The target is checked strictly before an
   * apply/start; non-target unrecoverable substitutions remain blank. */
  Map<String, String> materializeForRender(List<StoredValue> values) {
    Map<String, String> result = new LinkedHashMap<>();
    for (StoredValue value : values) {
      String clear = value.secret() ? secrets.openOrNull(value.value()) : value.value();
      result.put(value.key(), clear == null ? "" : clear);
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
      if (!secrets.isRecoverable(value.value())) {
        throw new ResourceConflictException("secret value is unrecoverable: " + value.key());
      }
    }
  }

  /** Values as the API exposes them: a secret is reported as set/recoverable, never returned. */
  List<ConfigValueDto> redact(List<StoredValue> values) {
    return values.stream().map(value -> {
      if (!value.secret()) return new ConfigValueDto(value.key(), value.value(), false, true, true);
      boolean set = value.value() != null;
      return new ConfigValueDto(value.key(), null, true, set, secrets.isRecoverable(value.value()));
    }).toList();
  }

  private static <T> List<T> list(List<T> value) {
    return value == null ? List.of() : List.copyOf(value);
  }
}
