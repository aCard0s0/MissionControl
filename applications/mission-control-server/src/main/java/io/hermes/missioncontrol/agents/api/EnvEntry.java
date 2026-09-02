package io.hermes.missioncontrol.agents.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One variable to write into a profile's {@code .env}.
 *
 * <p>{@code HermesEnvFile} re-checks both halves on the way to the file — this record is also
 * built by the template deploy path, which does not go through bean validation, and the
 * writer is reached by paths that carry no {@code EnvEntry} at all.
 *
 * <p>{@link #credentialId} is the alternative to {@link #value}: it names a saved credential to
 * take the value from, so a picker can fill a key without the browser ever holding it.
 * Whatever resolves it does so before the entry reaches {@code HermesSetup.putEnv}, which sees
 * only the two-argument form — so nothing below this record knows credentials exist.
 */
public record EnvEntry(
    @NotBlank @Pattern(regexp = EnvEntry.KEY_PATTERN, message = "invalid env key") String key,
    @Size(max = 8192) String value,
    @Size(max = 64) String credentialId) {

  /** A variable whose value is already in hand — every writer below the resolving layer. */
  public EnvEntry(String key, String value) {
    this(key, value, null);
  }

  /**
   * What a profile {@code .env} key may look like, for the four places that check it: this
   * annotation, {@code HermesEnvFile.assertWritable} — which every write goes through, including
   * the template deploy path that runs no bean validation — {@code ProfileTemplateService},
   * which refuses a template secret it could never write, and {@code CredentialEntryInput},
   * which refuses to save one. All of them wrote the expression out, and one carried a comment
   * saying it matched the others, which is the sort of agreement a constant makes true.
   *
   * <p>Deliberately narrower than a POSIX variable name: only the upper-case form hermes reads
   * out of a profile's {@code .env}.
   */
  public static final String KEY_PATTERN = "[A-Z][A-Z0-9_]{1,63}";
}
