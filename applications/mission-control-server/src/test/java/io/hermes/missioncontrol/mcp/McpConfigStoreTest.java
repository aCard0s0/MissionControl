package io.hermes.missioncontrol.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hermes.missioncontrol.errors.ResourceConflictException;
import io.hermes.missioncontrol.mcp.McpRequestValidator.Validated;
import io.hermes.missioncontrol.secrets.SecretCipher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The catalog's trust boundary.
 *
 * <p>Two rules here decide whether an operator can recover from a key mistake. A secret this
 * key can no longer decrypt must be <em>preserved</em>, not overwritten, so editing an
 * unrelated field never destroys the only copy of a credential; and it must be visibly
 * unrecoverable, so the operator knows to replace it rather than discovering the loss when a
 * start fails. Neither had coverage.
 *
 * <p>The ciphers are static: {@link SecretCipher} runs 210k PBKDF2 iterations per
 * construction, which is the whole cost of this suite if built per test.
 */
class McpConfigStoreTest {

  private static final SecretCipher CIPHER = new SecretCipher("test-secret", "", false);
  /** A different key: anything CIPHER wrote is unrecoverable to this one, and vice versa. */
  private static final SecretCipher OTHER_KEY = new SecretCipher("a-different-secret", "", false);

  private final McpConfigStore store = new McpConfigStore(CIPHER, new ObjectMapper());
  private final McpConfigStore storeWithWrongKey =
      new McpConfigStore(OTHER_KEY, new ObjectMapper());

  // ── storing ────────────────────────────────────────────────────────────────

  @Test
  void aSecretIsEncryptedAtRestAndAPlainValueIsNot() {
    StoredConfig config = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-live-1234", true, null),
            new ConfigValueInput("LOG_LEVEL", "debug", false, null))), null);

    StoredValue secret = valueOf(config, "API_TOKEN");
    assertTrue(secret.secret());
    assertFalse(secret.value().contains("sk-live-1234"), "the token is stored in the clear");
    assertEquals("sk-live-1234", CIPHER.decrypt(secret.value()));
    assertEquals("debug", valueOf(config, "LOG_LEVEL").value());
  }

  @Test
  void aBlankSecretInAnUpdateCarriesTheStoredValueForward() {
    // this is how the UI says "keep the value you already hold" — the form never
    // receives the ciphertext, so it cannot send it back
    StoredConfig existing = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-original", true, null))), null);

    StoredConfig updated = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "  ", true, null))), existing);

    assertEquals("sk-original", store.materialize(updated.environment()).get("API_TOKEN"));
  }

  @Test
  void aBlankSecretWithNothingToCarryForwardIsRejected() {
    IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
        () -> store.store(validated(
            List.of(new ConfigValueInput("API_TOKEN", null, true, null))), null));

    assertTrue(rejected.getMessage().contains("API_TOKEN"));
  }

  @Test
  void aClearedValueIsDroppedRatherThanStoredEmpty() {
    StoredConfig config = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-live", true, true),
            new ConfigValueInput("LOG_LEVEL", "debug", false, null))), null);

    assertEquals(List.of("LOG_LEVEL"), config.environment().stream().map(StoredValue::key).toList());
  }

  @Test
  void resavingRotatesARecoverableSecretOntoTheCurrentKey() {
    StoredConfig existing = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-original", true, null))), null);
    String before = valueOf(existing, "API_TOKEN").value();

    StoredConfig updated = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", null, true, null))), existing);

    String after = valueOf(updated, "API_TOKEN").value();
    assertFalse(before.equals(after), "a normal save is also the re-encryption opportunity");
    assertEquals("sk-original", CIPHER.decrypt(after));
  }

  // ── the unrecoverable-envelope contract ────────────────────────────────────

  @Test
  void anUnrecoverableSecretIsPreservedRatherThanOverwritten() {
    // written under one key, read back under another: the only copy of the credential is
    // the ciphertext, so a save that touches an unrelated field must not destroy it
    StoredConfig foreign = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-unrecoverable", true, null))), null);
    String envelope = valueOf(foreign, "API_TOKEN").value();

    StoredConfig resaved = storeWithWrongKey.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", null, true, null))), foreign);

    assertEquals(envelope, valueOf(resaved, "API_TOKEN").value(),
        "the envelope was replaced, losing the secret permanently");
  }

  @Test
  void anUnrecoverableSecretIsReportedAsSetButNotRecoverable() {
    StoredConfig foreign = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-unrecoverable", true, null))), null);

    ConfigValueDto dto = storeWithWrongKey.redact(foreign.environment()).getFirst();

    assertTrue(dto.secret());
    assertTrue(dto.set(), "the operator must not be told the value is missing");
    assertFalse(dto.recoverable(), "the operator must be told it has to be replaced");
    assertNull(dto.value());
  }

  @Test
  void anUnrecoverableSecretBlanksForARenderButBlocksAStart() {
    // rendering one host must not let an unrelated server's stale key block the whole file;
    // the target of an apply/start is checked strictly instead
    StoredConfig foreign = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-unrecoverable", true, null))), null);

    assertEquals("", storeWithWrongKey.materializeForRender(foreign.environment()).get("API_TOKEN"));

    ResourceConflictException blocked = assertThrows(ResourceConflictException.class,
        () -> storeWithWrongKey.assertRecoverable(foreign));
    assertTrue(blocked.getMessage().contains("unrecoverable"));
    assertTrue(blocked.getMessage().contains("API_TOKEN"));
  }

  @Test
  void anUnsetSecretAlsoBlocksAStart() {
    StoredConfig config = new StoredConfig("http", null, "example/mcp:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(new StoredValue("API_TOKEN", null, true)), List.of(), List.of(), null, List.of());

    ResourceConflictException blocked =
        assertThrows(ResourceConflictException.class, () -> store.assertRecoverable(config));
    assertTrue(blocked.getMessage().contains("not set"));
  }

  @Test
  void aSupportServiceSecretIsHeldToTheSameRules() {
    // the private database's password is as load-bearing as the server's own token
    StoredConfig foreign = store.store(withSupportSecret("POSTGRES_PASSWORD", "pg-secret"), null);

    assertEquals("pg-secret",
        store.materialize(foreign.supportServices().getFirst().environment()).get("POSTGRES_PASSWORD"));
    assertThrows(ResourceConflictException.class, () -> storeWithWrongKey.assertRecoverable(foreign));
  }

  @Test
  void aPlainValueIsAlwaysRecoverableAndReturnedInFull() {
    StoredConfig config = store.store(validated(
        List.of(new ConfigValueInput("LOG_LEVEL", "debug", false, null))), null);

    ConfigValueDto dto = store.redact(config.environment()).getFirst();
    assertFalse(dto.secret());
    assertEquals("debug", dto.value());
    assertTrue(dto.recoverable());
    // and it never reaches the cipher, so a stale key cannot block it
    assertEquals("debug", storeWithWrongKey.materializeForRender(config.environment()).get("LOG_LEVEL"));
  }

  // ── envelope round trip ────────────────────────────────────────────────────

  @Test
  void writeThenReadIsStableAndNullCollectionsBecomeEmptyOnes() {
    StoredConfig config = store.store(validated(
        List.of(new ConfigValueInput("API_TOKEN", "sk-live", true, null))), null);
    ServerRowFixture row = new ServerRowFixture(store.write(config));

    StoredConfig back = store.read(row.asRow());

    assertEquals("http", back.transport());
    assertEquals(1100, back.internalPort());
    assertEquals("sk-live", store.materialize(back.environment()).get("API_TOKEN"));
    // written non-null, but an early development row may carry nulls
    assertEquals(List.of(), back.args());
    assertEquals(List.of(), back.headers());
    assertEquals(List.of(), back.supportServices());
  }

  @Test
  void anUnreadableEnvelopeIsAConflictRatherThanAJacksonError() {
    // 409, not 500: the operator can delete and recreate the record, and a corrupt row must not
    // page whoever watches the 5xx rate
    ResourceConflictException failure = assertThrows(ResourceConflictException.class,
        () -> store.read(new ServerRowFixture("{not json").asRow()));

    assertTrue(failure.getMessage().contains("unreadable"));
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static StoredValue valueOf(StoredConfig config, String key) {
    return config.environment().stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow();
  }

  private static Validated validated(List<ConfigValueInput> environment) {
    return new Validated("Example", "an example server", "managed", "dh-local", "http", null,
        "example/mcp:latest", null, List.of(), List.of(), null, List.of(), 1100, null, "/mcp",
        null, environment, List.of(), List.of(), null, List.of());
  }

  private static Validated withSupportSecret(String key, String value) {
    SupportServiceRequest support = new SupportServiceRequest("database", "postgres:16-alpine",
        null, List.of(), List.of(), List.of(new ConfigValueInput(key, value, true, null)),
        List.of(), null);
    return new Validated("Example", "an example server", "managed", "dh-local", "http", null,
        "example/mcp:latest", null, List.of(), List.of(), null, List.of(), 1100, null, "/mcp",
        null, List.of(), List.of(), List.of(), null, List.of(support));
  }

  /** Only {@code configJson} is read by the store; the rest of the row is scaffolding. */
  private record ServerRowFixture(String configJson) {
    McpServerRepository.ServerRow asRow() {
      return new McpServerRepository.ServerRow("mcp-1", "Example", "", "managed", "dh-local",
          "mcp-1", configJson, "stopped", "missing", "idle", null, 1, 0, null, null, null, null,
          null, 0L, 0L);
    }
  }

  /** Guards the assumption the whole suite rests on. */
  @Test
  void theTwoKeysReallyDisagree() {
    String envelope = CIPHER.encrypt("value");
    assertEquals("value", CIPHER.decrypt(envelope));
    assertThrows(IllegalStateException.class, () -> OTHER_KEY.decrypt(envelope));
  }

  /** A value that never went through the cipher is passed through, not treated as a failure. */
  @Test
  void aLegacyPlaintextSecretStillMaterializes() {
    StoredConfig config = new StoredConfig("http", null, "example/mcp:latest", null,
        List.of(), List.of(), null, List.of(), 1100, null, "/mcp", null,
        List.of(new StoredValue("API_TOKEN", "sk-legacy-plaintext", true)), List.of(),
        List.of(), null, List.of());

    Map<String, String> materialized = store.materialize(config.environment());
    assertEquals("sk-legacy-plaintext", materialized.get("API_TOKEN"));
  }

  // ── carrying values across an edit ───────────────────────────────────────

  @Test
  void aNonSecretValueIsStoredAsWrittenAndAnAbsentOneAsEmpty() {
    StoredConfig stored = store.store(validated(
        List.of(new ConfigValueInput("ROOT", "/data", false, false),
            new ConfigValueInput("EMPTY", null, false, false))), null);

    assertEquals("/data", stored.environment().get(0).value());
    assertEquals("", stored.environment().get(1).value(), "an absent plain value is empty, not null");
  }

  @Test
  void aValueMarkedForClearingIsDroppedRatherThanStoredBlank() {
    StoredConfig existing = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", "secret", true, false))), null);

    StoredConfig cleared = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", null, true, true))), existing);

    assertTrue(cleared.environment().isEmpty());
  }

  @Test
  void aBlankSecretWithNothingStoredBehindItIsRefused() {
    // the UI sends blank for "keep what you have"; with nothing held that is a missing value
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> store.store(validated(List.of(new ConfigValueInput("TOKEN", "  ", true, false))), null));
    assertEquals("secret value is required: TOKEN", failure.getMessage());

    // and the same when the stored value under that key was never a secret
    StoredConfig plain = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", "plain", false, false))), null);
    assertEquals("secret value is required: TOKEN",
        assertThrows(IllegalArgumentException.class, () -> store.store(
            validated(List.of(new ConfigValueInput("TOKEN", "", true, false))), plain)).getMessage());
  }

  @Test
  void aBlankSecretRotatesTheStoredCiphertextRatherThanReusingIt() {
    StoredConfig existing = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", "secret", true, false))), null);
    String before = existing.environment().getFirst().value();

    StoredConfig kept = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", "   ", true, false))), existing);

    String after = kept.environment().getFirst().value();
    assertNotEquals(before, after, "a fresh envelope, so a key rotation reaches every value");
    assertEquals("secret", store.materialize(kept.environment()).get("TOKEN"));
  }

  @Test
  void anUnrecoverableStoredSecretIsPreservedRatherThanDestroyedByAnUnrelatedEdit() {
    // it cannot be re-encrypted, and dropping it would lose the only record that a value exists
    StoredValue foreign = new StoredValue("TOKEN", "enc:v1:not-mine", true);
    StoredConfig existing = configWith(List.of(foreign));

    StoredConfig kept = store.store(validated(
        List.of(new ConfigValueInput("TOKEN", null, true, false))), existing);

    assertEquals("enc:v1:not-mine", kept.environment().getFirst().value());
  }

  @Test
  void aSupportServicesSecretsAreCarriedAcrossAnEditTheSameWay() {
    StoredConfig existing = store.store(withSupportSecret("PGPASSWORD", "secret"), null);

    StoredConfig kept = store.store(withSupportSecret("PGPASSWORD", "   "), existing);

    assertEquals("secret",
        store.materialize(kept.supportServices().getFirst().environment()).get("PGPASSWORD"));
  }

  @Test
  void aNewSupportServiceHasNoPriorSecretsToCarry() {
    assertEquals("secret", store.materialize(
        store.store(withSupportSecret("PGPASSWORD", "secret"), null)
            .supportServices().getFirst().environment()).get("PGPASSWORD"));
  }

  // ── reading values back out ─────────────────────────────────────────────

  @Test
  void materialisingReadsPlainValuesAsWrittenAndDecryptsSecrets() {
    StoredConfig stored = store.store(validated(
        List.of(new ConfigValueInput("ROOT", "/data", false, false),
            new ConfigValueInput("TOKEN", "secret", true, false))), null);

    assertEquals("/data", store.materialize(stored.environment()).get("ROOT"));
    assertEquals("secret", store.materialize(stored.environment()).get("TOKEN"));
  }

  @Test
  void renderingSubstitutesBlankForAnUnrecoverableSecretRatherThanFailingTheWholeHost() {
    // one server's stale key must not block lifecycle operations for every other server on
    // the host; the strict check happens against the target of the operation
    StoredConfig config = configWith(List.of(
        new StoredValue("TOKEN", "enc:v1:not-mine", true),
        new StoredValue("ROOT", "/data", false),
        new StoredValue("MISSING", null, true)));

    Map<String, String> rendered = store.materializeForRender(config.environment());

    assertEquals("", rendered.get("TOKEN"));
    assertEquals("/data", rendered.get("ROOT"));
    assertEquals("", rendered.get("MISSING"));
  }

  @Test
  void redactionReportsWhetherASecretIsSetAndStillRecoverable() {
    StoredConfig config = configWith(List.of(
        new StoredValue("ROOT", "/data", false),
        new StoredValue("GOOD", store.store(validated(
            List.of(new ConfigValueInput("GOOD", "secret", true, false))), null)
            .environment().getFirst().value(), true),
        new StoredValue("STALE", "enc:v1:not-mine", true),
        new StoredValue("UNSET", null, true)));

    List<ConfigValueDto> redacted = store.redact(config.environment());

    assertEquals("/data", redacted.get(0).value(), "a plain value is not a secret");
    assertNull(redacted.get(1).value(), "a secret is never returned");
    assertTrue(redacted.get(1).set() && redacted.get(1).recoverable());
    assertTrue(redacted.get(2).set());
    assertEquals(false, redacted.get(2).recoverable(), "a stale envelope must be re-entered");
    assertEquals(false, redacted.get(3).set());
  }

  @Test
  void anUnrecoverableSecretBlocksTheServerItBelongsTo() {
    StoredConfig config = configWith(List.of(new StoredValue("TOKEN", "enc:v1:not-mine", true)));

    assertEquals("secret value is unrecoverable: TOKEN",
        assertThrows(ResourceConflictException.class, () -> store.assertRecoverable(config)).getMessage());
  }

  private StoredConfig configWith(List<StoredValue> environment) {
    return new StoredConfig("http", null, "example/mcp:latest", null, List.of(), List.of(), null,
        List.of(), 1100, null, "/mcp", null, environment, List.of(), List.of(), null, List.of());
  }
}
