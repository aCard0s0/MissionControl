package io.hermes.missioncontrol.hermes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Round-trip, key-rotation, and key-management guards for {@link SecretCipher}. */
class SecretCipherTest {

  private static SecretCipher cipher(String key, String previous) {
    return new SecretCipher(key, previous, true);
  }

  @Test
  void roundTripsThroughTheEnvelope() {
    SecretCipher c = cipher("a-strong-test-key", "");
    String enc = c.encrypt("sk-ant-secret-value");
    assertTrue(enc.startsWith("enc:v1:"), "encrypted form is the versioned envelope");
    assertFalse(enc.contains("sk-ant-secret-value"), "ciphertext does not embed the plaintext");
    assertEquals("sk-ant-secret-value", c.decrypt(enc));
  }

  @Test
  void differentIvsProduceDifferentCiphertexts() {
    SecretCipher c = cipher("a-strong-test-key", "");
    assertFalse(c.encrypt("same").equals(c.encrypt("same")), "random IV per encrypt");
  }

  @Test
  void nonEnvelopeInputPassesThroughUnchanged() {
    SecretCipher c = cipher("a-strong-test-key", "");
    assertEquals("legacy-plaintext", c.decrypt("legacy-plaintext"));
    assertNull(c.decrypt(null));
    assertNull(c.encrypt(null));
  }

  @Test
  void previousKeyDecryptsAfterRotation() {
    String enc = cipher("old-key", "").encrypt("rotate-me");
    SecretCipher rotated = cipher("new-key", "old-key");
    assertEquals("rotate-me", rotated.decrypt(enc), "decrypt falls back to the previous key");
    // and a re-encrypt now uses the new key (old key alone can no longer read it)
    String reEnc = rotated.encrypt("rotate-me");
    assertThrows(IllegalStateException.class, () -> cipher("old-key", "").decrypt(reEnc));
  }

  @Test
  void wrongKeyWithoutPreviousFailsLoudly() {
    String enc = cipher("old-key", "").encrypt("v");
    assertThrows(IllegalStateException.class, () -> cipher("new-key", "").decrypt(enc));
  }

  @Test
  void blankKeyWithoutDevOptInFailsFast() {
    assertThrows(IllegalStateException.class, () -> new SecretCipher("", "", false));
    assertThrows(IllegalStateException.class, () -> new SecretCipher(null, "", false));
  }

  @Test
  void devKeyWorksOnlyWhenExplicitlyAllowed() {
    SecretCipher dev = new SecretCipher("", "", true);
    assertEquals("v", dev.decrypt(dev.encrypt("v")));
  }
}
