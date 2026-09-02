package io.hermes.missioncontrol.secrets;

/**
 * A template secret on the way in. Blank/absent {@code value} keeps the stored one.
 *
 * <p>{@code credentialId} is the alternative to {@code value}: it names a saved credential to
 * copy the envelope for this key from, so a blueprint can carry a key the editor never held.
 * The copy is ciphertext to ciphertext — both sides are sealed under the same
 * {@code MC_SECRET_KEY} — so nothing decrypts on that path.
 */
public record SecretInput(String key, String value, String credentialId) {

  /** A secret whose value was typed, or deliberately left blank to keep the stored one. */
  public SecretInput(String key, String value) {
    this(key, value, null);
  }
}
