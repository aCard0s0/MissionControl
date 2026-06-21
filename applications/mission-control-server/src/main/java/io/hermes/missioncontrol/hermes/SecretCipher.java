package io.hermes.missioncontrol.hermes;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Symmetric encryption for profile-template secrets (API keys, tokens) stored at
 * rest in the dashboard DB. AES-256-GCM with a key derived from
 * {@code MC_SECRET_KEY} via PBKDF2-HMAC-SHA256. No external dependency — JDK
 * javax.crypto only.
 *
 * <p>Stored form: {@code enc:v1:<base64(iv ++ ciphertext ++ gcmTag)>}. Values that
 * are not in this form (legacy/plaintext) are returned unchanged by
 * {@link #decrypt}, so the store degrades rather than corrupting data.
 *
 * <p><b>Key management.</b> {@code MC_SECRET_KEY} must be set in any real
 * deployment — startup fails fast otherwise, unless {@code mc.allow-dev-key=true}
 * explicitly opts into a built-in dev key (local development only; the key is in
 * the source, so anything encrypted with it is readable by anyone with the repo).
 * To rotate, set the old value as {@code MC_SECRET_KEY_PREVIOUS}: {@link #decrypt}
 * falls back to it, and any re-saved secret is re-encrypted under the new key.
 */
@Component
public class SecretCipher {

  private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
  private static final String PREFIX = "enc:v1:";
  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;
  private static final String DEV_KEY = "mission-control-dev-secret-change-me";
  // App-specific PBKDF2 parameters. The salt is fixed (a single app-wide key, not
  // per-record) so it binds the KDF to this application; the iteration count adds
  // the work factor a bare hash lacks, slowing offline brute-force of a weak key.
  private static final byte[] KDF_SALT =
      "io.hermes.missioncontrol/secret-cipher/v1".getBytes(StandardCharsets.UTF_8);
  private static final int KDF_ITERATIONS = 210_000;
  private static final int KEY_BITS = 256;

  private final SecretKeySpec key;
  private final SecretKeySpec previousKey;   // null unless MC_SECRET_KEY_PREVIOUS set
  private final SecureRandom random = new SecureRandom();

  public SecretCipher(
      @Value("${mc.secret-key:}") String secret,
      @Value("${mc.secret-key-previous:}") String previousSecret,
      @Value("${mc.allow-dev-key:false}") boolean allowDevKey) {
    String material;
    if (secret == null || secret.isBlank()) {
      if (!allowDevKey) {
        throw new IllegalStateException(
            "MC_SECRET_KEY is not set. Set it to a strong random value in any real "
                + "deployment, or set MC_ALLOW_DEV_KEY=true to use the built-in dev key "
                + "for local development only.");
      }
      log.warn("MC_SECRET_KEY is not set — using the built-in dev key (MC_ALLOW_DEV_KEY=true). "
          + "Profile-template secrets are readable by anyone with the source; never do this in production.");
      material = DEV_KEY;
    } else {
      material = secret;
    }
    this.key = new SecretKeySpec(deriveKey(material), "AES");
    this.previousKey = (previousSecret == null || previousSecret.isBlank())
        ? null : new SecretKeySpec(deriveKey(previousSecret), "AES");
  }

  /** Returns the {@code enc:v1:} envelope for {@code plain}, or null when plain is null. */
  public String encrypt(String plain) {
    if (plain == null) {
      return null;
    }
    try {
      byte[] iv = new byte[IV_LEN];
      random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ciphertext.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("secret encryption failed", e);
    }
  }

  /** Reverses {@link #encrypt}. Non-enveloped input is returned as-is. Tries the
   *  current key, then the previous key (rotation) before giving up. */
  public String decrypt(String stored) {
    if (stored == null) {
      return null;
    }
    if (!stored.startsWith(PREFIX)) {
      return stored;
    }
    byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
    try {
      return decryptWith(all, key);
    } catch (Exception primaryFailure) {
      if (previousKey != null) {
        try {
          return decryptWith(all, previousKey);
        } catch (Exception ignored) {
          // fall through to the primary failure below
        }
      }
      throw new IllegalStateException("secret decryption failed", primaryFailure);
    }
  }

  private static String decryptWith(byte[] all, SecretKeySpec withKey) throws Exception {
    byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
    byte[] ciphertext = Arrays.copyOfRange(all, IV_LEN, all.length);
    Cipher cipher = Cipher.getInstance(TRANSFORM);
    cipher.init(Cipher.DECRYPT_MODE, withKey, new GCMParameterSpec(TAG_BITS, iv));
    return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
  }

  private static byte[] deriveKey(String material) {
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      KeySpec spec = new PBEKeySpec(material.toCharArray(), KDF_SALT, KDF_ITERATIONS, KEY_BITS);
      return factory.generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("key derivation failed", e);
    }
  }
}
