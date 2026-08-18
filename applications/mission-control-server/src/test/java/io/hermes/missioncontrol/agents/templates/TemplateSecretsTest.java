package io.hermes.missioncontrol.agents.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.hermes.missioncontrol.secrets.SecretCipher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The template side of the same trust boundary {@link io.hermes.missioncontrol.mcp} has.
 *
 * <p>A template's MCP snapshot is often the only place a captured header or stdio credential
 * exists. So a value this key can no longer decrypt must survive a save rather than being
 * replaced, and one unreadable secret must not take the rest of a deploy with it — the
 * template is still usable, minus that value.
 *
 * <p>The ciphers are static: {@link SecretCipher} runs 210k PBKDF2 iterations per
 * construction.
 */
class TemplateSecretsTest {

  private static final SecretCipher CIPHER = new SecretCipher("test-secret", "", false);
  /** A different key: anything CIPHER wrote is unrecoverable to this one. */
  private static final SecretCipher OTHER_KEY = new SecretCipher("a-different-secret", "", false);

  private final TemplateSecrets secrets = new TemplateSecrets(CIPHER);
  private final TemplateSecrets wrongKey = new TemplateSecrets(OTHER_KEY);

  // ── round trip ─────────────────────────────────────────────────────────────

  @Test
  void valuesAreEncryptedAtRestAndComeBackInTheClear() {
    Map<String, String> input = new LinkedHashMap<>();
    input.put("Authorization", "Bearer sk-live-1234");
    input.put("X-Tenant", "acme");

    List<TemplateMcpConfigValue> stored = secrets.encryptValues(input);

    assertEquals(List.of("Authorization", "X-Tenant"),
        stored.stream().map(TemplateMcpConfigValue::key).toList());
    assertFalse(stored.getFirst().encryptedValue().contains("sk-live-1234"),
        "the header is stored in the clear");
    assertEquals(input, secrets.decryptValues(stored));
  }

  @Test
  void nullAndEmptyInputsAreAcceptedAndProduceNothing() {
    assertEquals(List.of(), secrets.encryptValues(null));
    assertEquals(List.of(), secrets.encryptValues(Map.of()));
    assertEquals(Map.of(), secrets.decryptValues(null));
    assertEquals(List.of(), secrets.reencryptValues(null));
  }

  @Test
  void entriesWithoutAUsableKeyOrValueAreSkipped() {
    Map<String, String> input = new LinkedHashMap<>();
    input.put("GOOD", "value");
    input.put("NO_VALUE", null);
    input.put(null, "orphan");

    assertEquals(List.of("GOOD"),
        secrets.encryptValues(input).stream().map(TemplateMcpConfigValue::key).toList());

    // and on the way back out, a blank key cannot become a config entry
    Map<String, String> back = secrets.decryptValues(List.of(
        new TemplateMcpConfigValue("GOOD", secrets.encrypt("value")),
        new TemplateMcpConfigValue("   ", secrets.encrypt("blank key")),
        new TemplateMcpConfigValue(null, secrets.encrypt("null key"))));
    assertEquals(Map.of("GOOD", "value"), back);
  }

  // ── the unrecoverable-envelope contract ────────────────────────────────────

  @Test
  void aBadKeyYieldsNullRatherThanThrowing() {
    // one unreadable secret must not fail the whole template read or deploy
    String envelope = secrets.encrypt("sk-unrecoverable");

    assertNull(wrongKey.decryptOrNull(envelope));
    assertNull(wrongKey.decryptOrNull(null));
  }

  @Test
  void anUnrecoverableValueIsDroppedFromADeployRatherThanFailingIt() {
    List<TemplateMcpConfigValue> stored = List.of(
        new TemplateMcpConfigValue("Authorization", secrets.encrypt("sk-unrecoverable")),
        new TemplateMcpConfigValue("X-Tenant", OTHER_KEY.encrypt("acme")));

    Map<String, String> usable = wrongKey.decryptValues(stored);

    assertEquals(Map.of("X-Tenant", "acme"), usable,
        "the readable header was lost along with the unreadable one");
  }

  @Test
  void resavingPreservesAnUnrecoverableEnvelopeAndRotatesTheRestOntoTheCurrentKey() {
    String foreign = secrets.encrypt("sk-unrecoverable");
    String own = OTHER_KEY.encrypt("sk-rotatable");
    List<TemplateMcpConfigValue> stored = List.of(
        new TemplateMcpConfigValue("Authorization", foreign),
        new TemplateMcpConfigValue("X-Tenant", own));

    List<TemplateMcpConfigValue> resaved = wrongKey.reencryptValues(stored);

    assertEquals(foreign, resaved.getFirst().encryptedValue(),
        "the envelope was replaced, losing the secret permanently");
    String rotated = resaved.get(1).encryptedValue();
    assertFalse(own.equals(rotated), "a normal save is also the re-encryption opportunity");
    assertEquals("sk-rotatable", OTHER_KEY.decrypt(rotated));
  }

  @Test
  void nullEntriesInAStoredListAreIgnoredRatherThanCrashingASave() {
    // early/hand-edited template JSON can carry a null element
    List<TemplateMcpConfigValue> stored = java.util.Arrays.asList(
        new TemplateMcpConfigValue("GOOD", secrets.encrypt("value")), null);

    assertEquals(1, secrets.reencryptValues(stored).size());
    assertEquals(Map.of("GOOD", "value"), secrets.decryptValues(stored));
  }

  // ── redaction ──────────────────────────────────────────────────────────────

  @Test
  void redactionKeepsEveryKeyAndWithholdsEveryValue() {
    McpServerSpec spec = new McpServerSpec("reports", "sse", "https://mcp.example.test/sse",
        null, null, true, "mcp-seed-postgres",
        List.of(new TemplateMcpConfigValue("DATABASE_URL", secrets.encrypt("postgres://…"))),
        List.of(new TemplateMcpConfigValue("Authorization", secrets.encrypt("Bearer sk-live"))));

    McpServerSpec redacted = TemplateSecrets.redacted(spec);

    // the connection shape stays visible so the operator can see what the template holds
    assertEquals("reports", redacted.name());
    assertEquals("sse", redacted.transport());
    assertEquals("https://mcp.example.test/sse", redacted.url());
    assertEquals(true, redacted.enabled());
    // keys visible, values gone, and the input-only catalog id never echoed back
    assertEquals(List.of("DATABASE_URL"),
        redacted.environment().stream().map(TemplateMcpConfigValue::key).toList());
    assertNull(redacted.environment().getFirst().encryptedValue());
    assertEquals(List.of("Authorization"),
        redacted.headers().stream().map(TemplateMcpConfigValue::key).toList());
    assertNull(redacted.headers().getFirst().encryptedValue());
    assertNull(redacted.sourceServerId());
  }

  @Test
  void redactingASpecWithNoCapturedValuesIsSafe() {
    McpServerSpec spec =
        new McpServerSpec("tools", "stdio", null, "uvx", "mcp-server", false);

    McpServerSpec redacted = TemplateSecrets.redacted(spec);

    assertEquals(List.of(), redacted.environment());
    assertEquals(List.of(), redacted.headers());
    assertEquals("uvx", redacted.command());
  }

  /** Guards the assumption the whole suite rests on. */
  @Test
  void theTwoKeysReallyDisagree() {
    String envelope = CIPHER.encrypt("value");
    assertEquals("value", CIPHER.decrypt(envelope));
    assertNull(wrongKey.decryptOrNull(envelope));
    assertTrue(envelope.startsWith("enc:v1:"));
  }
}
