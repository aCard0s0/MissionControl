package io.hermes.missioncontrol.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The four rules every stored credential obeys, in the one place that now owns them.
 *
 * <p>They used to be implemented twice — once for the MCP catalog, once for profile templates —
 * and had drifted on two of the four: a blank submission with nothing stored was refused in one
 * and silently dropped in the other, and only one of the two treated a save as a re-encryption
 * opportunity. These tests are what stops them drifting again.
 *
 * <p>The ciphers are static: {@link SecretCipher} runs 210k PBKDF2 iterations per construction.
 */
class SecretsAtRestTest {

  private static final SecretCipher CIPHER = new SecretCipher("test-secret", "", false);
  /** A different key: anything CIPHER wrote is unrecoverable to this one. */
  private static final SecretCipher OTHER_KEY = new SecretCipher("a-different-secret", "", false);

  private final SecretsAtRest secrets = new SecretsAtRest(CIPHER);
  private final SecretsAtRest wrongKey = new SecretsAtRest(OTHER_KEY);

  // ── rule 1: seal on the way in, keep on blank ──────────────────────────────

  @Test
  void aSubmittedValueIsSealedAndComesBackInTheClear() {
    String envelope = secrets.seal("sk-live-1234");

    assertFalse(envelope.contains("sk-live-1234"), "the value is stored in the clear");
    assertTrue(envelope.startsWith("enc:v1:"));
    assertEquals("sk-live-1234", secrets.open(envelope));
  }

  @Test
  void aSubmittedValueWinsOverWhateverIsStored() {
    String prior = secrets.seal("sk-old");

    assertEquals("sk-new", secrets.open(secrets.sealOrKeep("sk-new", prior, "API_TOKEN")));
  }

  @Test
  void aBlankSubmissionKeepsTheStoredValue() {
    // no editor ever receives ciphertext, so blank is the only way it can say "unchanged"
    String prior = secrets.seal("sk-keep-me");

    assertEquals("sk-keep-me", secrets.open(secrets.sealOrKeep("", prior, "API_TOKEN")));
    assertEquals("sk-keep-me", secrets.open(secrets.sealOrKeep("   ", prior, "API_TOKEN")));
    assertEquals("sk-keep-me", secrets.open(secrets.sealOrKeep(null, prior, "API_TOKEN")));
  }

  @Test
  void aBlankSubmissionWithNothingToKeepIsRefused() {
    // the alternative — dropping it — reports a success that did not happen, and the caller
    // discovers the missing credential when something tries to use it
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
        () -> secrets.sealOrKeep("", null, "OPENAI_API_KEY"));

    // the key names the secret; a message must never carry the value itself
    assertEquals("secret value is required: OPENAI_API_KEY", refused.getMessage());
  }

  // ── rule 2: a save is a rotation opportunity ───────────────────────────────

  @Test
  void keepingAValueReSealsItUnderTheCurrentKey() {
    // this is what lets MC_SECRET_KEY_PREVIOUS stop being load-bearing: one save per secret
    SecretsAtRest rotated = new SecretsAtRest(new SecretCipher("test-secret", "a-different-secret", false));
    String writtenWithOldKey = OTHER_KEY.encrypt("sk-rotatable");

    String kept = rotated.sealOrKeep("", writtenWithOldKey, "API_TOKEN");

    assertFalse(writtenWithOldKey.equals(kept), "the envelope was carried over verbatim");
    assertEquals("sk-rotatable", CIPHER.decrypt(kept), "not re-sealed under the current key");
  }

  // ── rule 3: an unopenable envelope is preserved ────────────────────────────

  @Test
  void anEnvelopeThisKeyCannotOpenIsCarriedOverUntouched() {
    // it is the only copy of the credential; replacing it destroys it permanently, and the
    // operator needs to see recoverable=false and re-enter the value instead
    String foreign = secrets.seal("sk-unrecoverable");

    assertEquals(foreign, wrongKey.reseal(foreign));
    assertEquals(foreign, wrongKey.sealOrKeep("", foreign, "API_TOKEN"));
    assertFalse(wrongKey.isRecoverable(foreign));
  }

  // ── rule 4: degrade, do not fail ───────────────────────────────────────────

  @Test
  void anUnopenableEnvelopeReadsAsNullRatherThanThrowing() {
    // one unreadable secret must not take a whole read, render or deploy with it
    String foreign = secrets.seal("sk-unrecoverable");

    assertNull(wrongKey.openOrNull(foreign));
    assertThrows(RuntimeException.class, () -> wrongKey.open(foreign),
        "the strict read is what stops a blank being handed to something that needs the value");
  }

  @Test
  void nullsAreAcceptedEverywhereAValueMayBeAbsent() {
    assertNull(secrets.seal(null));
    assertNull(secrets.reseal(null));
    assertNull(secrets.openOrNull(null));
    assertFalse(secrets.isRecoverable(null), "an absent envelope is not a recoverable one");
  }
}
