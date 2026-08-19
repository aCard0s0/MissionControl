package io.hermes.missioncontrol.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What it means to hold a secret at rest: the four rules every stored credential in this
 * application obeys, over the {@code enc:v1:} envelopes {@link SecretCipher} produces.
 *
 * <ol>
 *   <li><b>Seal on the way in, keep on blank.</b> No editor ever receives ciphertext, so a
 *       blank submission is how it says "keep the value you already hold" — see
 *       {@link #sealOrKeep}. Submitting nothing with nothing to keep is an error, not a
 *       silent no-op.
 *   <li><b>A save is a rotation opportunity.</b> Keeping a value re-seals it under the
 *       current key, so {@code MC_SECRET_KEY_PREVIOUS} stops being load-bearing as soon as
 *       each secret has been through one save.
 *   <li><b>An unopenable envelope is preserved, never destroyed.</b> A wrong key or corrupt
 *       ciphertext must not be overwritten by an unrelated edit — it is the only copy of the
 *       credential, and the operator has to be able to see the loss and replace it.
 *   <li><b>Degrade, do not fail.</b> One unreadable secret must not take a whole read,
 *       render or deploy with it — {@link #openOrNull}.
 * </ol>
 *
 * <p>Here rather than in the two packages that store secrets. {@code mcp/McpConfigStore} and
 * {@code agents/templates/TemplateSecrets} each implemented all four over their own value
 * record, and they had already drifted: a <em>new</em> secret submitted blank was a 400 in
 * the catalog and a silent omission in a template, which loses the credential and reports
 * success. Rule 2 had drifted the other way — the template MCP snapshots documented
 * themselves as "matching the behavior of template-owned API keys", which did not re-seal at
 * all.
 *
 * <p>The wire and storage shapes stay with their owners: this class knows nothing about
 * {@code StoredValue}, {@code TemplateMcpConfigValue}, or which DTO a redaction becomes. It
 * owns one envelope at a time.
 */
@Component
public class SecretsAtRest {

  private static final Logger log = LoggerFactory.getLogger(SecretsAtRest.class);

  private final SecretCipher cipher;

  public SecretsAtRest(SecretCipher cipher) {
    this.cipher = cipher;
  }

  /** The envelope for a value the caller holds in the clear. */
  public String seal(String clear) {
    return cipher.encrypt(clear);
  }

  /**
   * The envelope to store for a submitted value, keeping {@code priorEnvelope} when the
   * submission is blank.
   *
   * <p>A kept envelope is re-sealed under the current key when it can still be opened, and
   * passed through untouched when it cannot — rules 2 and 3. A blank submission with no prior
   * envelope throws: the caller asked to store a secret and supplied none, and the
   * alternative (dropping it) reports a success that did not happen.
   *
   * @param key names the secret in the failure message; never a value, always an identifier
   */
  public String sealOrKeep(String submitted, String priorEnvelope, String key) {
    if (submitted != null && !submitted.isBlank()) {
      return seal(submitted);
    }
    if (priorEnvelope == null) {
      throw new IllegalArgumentException("secret value is required: " + key);
    }
    return reseal(priorEnvelope);
  }

  /**
   * The same envelope re-sealed under the current key, or the original when this key can no
   * longer open it.
   *
   * <p>Returning the unopenable envelope is the point: replacing it with a fresh encryption
   * of nothing would destroy the only copy of the credential.
   */
  public String reseal(String envelope) {
    if (envelope == null) return null;
    String clear = openOrNull(envelope);
    return clear == null ? envelope : seal(clear);
  }

  /**
   * The value in the clear. Throws when this key cannot open the envelope — for the callers
   * that are about to hand the value to something that needs it and must not substitute a
   * blank.
   */
  public String open(String envelope) {
    return cipher.decrypt(envelope);
  }

  /**
   * The value in the clear, or null when this key can no longer open the envelope.
   *
   * <p>Rule 4. The loss is logged because it is otherwise invisible at the point it happens:
   * the caller substitutes a blank or drops the entry, and the operator sees a credential
   * that silently stopped working.
   */
  public String openOrNull(String envelope) {
    if (envelope == null) return null;
    try {
      return cipher.decrypt(envelope);
    } catch (RuntimeException unrecoverable) {
      log.warn("a stored secret could not be decrypted (check MC_SECRET_KEY): {}",
          unrecoverable.getMessage());
      return null;
    }
  }

  /**
   * Whether this key can still open the envelope — what the API reports as
   * {@code recoverable}, and what the pre-flight check before an apply or a connect asserts.
   */
  public boolean isRecoverable(String envelope) {
    if (envelope == null) return false;
    try {
      cipher.decrypt(envelope);
      return true;
    } catch (RuntimeException unrecoverable) {
      return false;
    }
  }
}
